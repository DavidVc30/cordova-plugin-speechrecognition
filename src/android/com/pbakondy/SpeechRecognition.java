// https://developer.android.com/reference/android/speech/SpeechRecognizer.html

package com.pbakondy;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.os.SystemClock;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;

import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import android.util.Log;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SpeechRecognition extends CordovaPlugin {

  private static final String LOG_TAG = "SpeechRecognition";

  private static final int REQUEST_CODE_PERMISSION = 2001;
  private static final int REQUEST_CODE_SPEECH = 2002;
  private static final String IS_RECOGNITION_AVAILABLE = "isRecognitionAvailable";
  private static final String START_LISTENING = "startListening";
  private static final String STOP_LISTENING = "stopListening";
  private static final String GET_SUPPORTED_LANGUAGES = "getSupportedLanguages";
  private static final String HAS_PERMISSION = "hasPermission";
  private static final String REQUEST_PERMISSION = "requestPermission";
  private static final int MAX_RESULTS = 5;
  private static final String NOT_AVAILABLE = "Speech recognition service is not available on the system.";
  private static final String MISSING_PERMISSION = "Missing permission";

  private JSONArray mLastPartialResults = new JSONArray();

  private static final String RECORD_AUDIO_PERMISSION = Manifest.permission.RECORD_AUDIO;

  private CallbackContext callbackContext;
  private CallbackContext diagnosticCallback;
  private CallbackContext sessionCallback;
  private int sessionCounter = 0;
  private int currentSession = 0;
  private int recognizerCounter = 0;
  private int currentRecognizerId = 0;
  private boolean sessionActive = false;
  private boolean sessionStopping = false;
  private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable forcedShutdown = new Runnable() {
    @Override public void run() {
      if (sessionActive && sessionStopping) {
        diagnostic("recovery", "Stop timeout: destroying recognizer");
        finishSession(currentSession, "Cancelled", null);
      }
    }
  };
  private void destroyRecognizer() {
    mainHandler.removeCallbacks(forcedShutdown);
    if (recognizer != null) {
      try { recognizer.cancel(); } catch (Exception ignored) {}
      try { recognizer.destroy(); } catch (Exception e) { Log.w(LOG_TAG, "destroy failed", e); }
      recognizer = null;
      diagnostic("destroy", "Recognizer #" + currentRecognizerId + " destroyed");
    }
  }
  private void finishSession(int id, String error, JSONArray values) {
    if (id != currentSession || !sessionActive) return;
    sessionActive = false;
    sessionStopping = false;
    CallbackContext target = sessionCallback;
    sessionCallback = null;
    destroyRecognizer();
    if (target != null) {
      if (error != null) target.error(error);
      else target.success(values == null ? new JSONArray() : values);
    }
  }

  private long readyAt = -1, speechAt = -1, endAt = -1;
  private int configuredComplete = -1, configuredPossible = -1, configuredMinimum = -1;
  private void diagnostic(String event, String detail) {
    if (diagnosticCallback == null) return;
    try {
      JSONObject data = new JSONObject();
      data.put("event", event);
      data.put("sessionId", currentSession);
      data.put("recognizerId", currentRecognizerId);
      data.put("timestampMs", System.currentTimeMillis());
      data.put("elapsedMs", readyAt < 0 ? -1 : SystemClock.elapsedRealtime() - readyAt);
      data.put("readyToSpeechMs", readyAt >= 0 && speechAt >= 0 ? speechAt - readyAt : -1);
      data.put("speechToEndMs", speechAt >= 0 && endAt >= 0 ? endAt - speechAt : -1);
      data.put("endToResultMs", endAt >= 0 && ("results".equals(event) || "error".equals(event)) ? SystemClock.elapsedRealtime() - endAt : -1);
      data.put("completeSilenceMs", configuredComplete);
      data.put("possiblyCompleteSilenceMs", configuredPossible);
      data.put("minimumLengthMs", configuredMinimum);
      data.put("detail", detail);
      PluginResult result = new PluginResult(PluginResult.Status.OK, data);
      result.setKeepCallback(true);
      diagnosticCallback.sendPluginResult(result);
    } catch (JSONException e) { Log.e(LOG_TAG, "Diagnostic event failure", e); }
  }

  private LanguageDetailsChecker languageDetailsChecker;
  private Activity activity;
  private Context context;
  private View view;
  private SpeechRecognizer recognizer;

  @Override
  public void initialize(CordovaInterface cordova, CordovaWebView webView) {
    super.initialize(cordova, webView);

    activity = cordova.getActivity();
    context = webView.getContext();
    view = webView.getView();

    // Create a fresh recognizer for each session, on the main thread.
  }

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
    if (!"subscribeDiagnostics".equals(action) && !"unsubscribeDiagnostics".equals(action) && !STOP_LISTENING.equals(action) && !START_LISTENING.equals(action)) this.callbackContext = callbackContext;

    Log.d(LOG_TAG, "execute() action " + action);

    try {
      if ("subscribeDiagnostics".equals(action)) {
        diagnosticCallback = callbackContext;
        PluginResult subscribed = new PluginResult(PluginResult.Status.NO_RESULT);
        subscribed.setKeepCallback(true);
        callbackContext.sendPluginResult(subscribed);
        return true;
      }
      if ("unsubscribeDiagnostics".equals(action)) {
        if (diagnosticCallback != null) { diagnosticCallback.success(); diagnosticCallback = null; }
        callbackContext.success();
        return true;
      }
      if (IS_RECOGNITION_AVAILABLE.equals(action)) {
        boolean available = isRecognitionAvailable();
        PluginResult result = new PluginResult(PluginResult.Status.OK, available);
        callbackContext.sendPluginResult(result);
        return true;
      }

      if (START_LISTENING.equals(action)) {
        if (!isRecognitionAvailable()) {
          callbackContext.error(NOT_AVAILABLE);
          return true;
        }
        if (!audioPermissionGranted(RECORD_AUDIO_PERMISSION)) {
          callbackContext.error(MISSING_PERMISSION);
          return true;
        }

        String lang = args.optString(0);
        if (lang == null || lang.isEmpty() || lang.equals("null")) {
          lang = Locale.getDefault().toString();
        }

        int matches = args.optInt(1, MAX_RESULTS);

        String prompt = args.optString(2);
        if (prompt == null || prompt.isEmpty() || prompt.equals("null")) {
          prompt = null;
        }

        mLastPartialResults = new JSONArray();
        Boolean showPartial = args.optBoolean(3, false);
        Boolean showPopup = args.optBoolean(4, true);
        int completeSilenceMs = args.optInt(5, -1);
        int possiblyCompleteSilenceMs = args.optInt(6, -1);
        int minimumLengthMs = args.optInt(7, -1);
        final CallbackContext requestedCallback = callbackContext;
        configuredComplete = completeSilenceMs;
        configuredPossible = possiblyCompleteSilenceMs;
        configuredMinimum = minimumLengthMs;
        final String requestedLanguage = lang;
        final int requestedMatches = matches;
        final String requestedPrompt = prompt;
        final boolean requestedPartial = showPartial;
        final boolean requestedPopup = showPopup;
        final int requestedComplete = completeSilenceMs;
        final int requestedPossible = possiblyCompleteSilenceMs;
        final int requestedMinimum = minimumLengthMs;
        mainHandler.post(new Runnable() {
          @Override public void run() {
            if (sessionActive) {
              diagnostic("blocked", "Rejected overlapping native start");
              requestedCallback.error("RecognitionService busy");
              return;
            }
            sessionActive = true;
            sessionStopping = false;
            sessionCallback = requestedCallback;
            currentSession = ++sessionCounter;
            readyAt = speechAt = endAt = -1;
            diagnostic("start", "Native start requested");
            try {
              if (!requestedPopup) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
                currentRecognizerId = ++recognizerCounter;
                recognizer.setRecognitionListener(new SpeechRecognitionListener(currentSession));
                diagnostic("create", "Recognizer #" + currentRecognizerId + " created");
              }
              startListening(requestedLanguage, requestedMatches, requestedPrompt,
                  requestedPartial, requestedPopup, requestedComplete, requestedPossible, requestedMinimum);
            } catch (Exception e) {
              diagnostic("error", "Native startup exception: " + e);
              finishSession(currentSession, e.toString(), null);
            }
          }
        });

        return true;
      }

      if (STOP_LISTENING.equals(action)) {
        final CallbackContext callbackContextStop = callbackContext;
        view.post(new Runnable() {
          @Override
          public void run() {
            if (sessionActive && recognizer != null && !sessionStopping) {
              sessionStopping = true;
              diagnostic("stop", "Stop requested; awaiting terminal callback");
              recognizer.stopListening();
              mainHandler.postDelayed(forcedShutdown, 2500);
            }
            callbackContextStop.success();
          }
        });
        return true;
      }

      if (GET_SUPPORTED_LANGUAGES.equals(action)) {
        getSupportedLanguages();
        return true;
      }

      if (HAS_PERMISSION.equals(action)) {
        hasAudioPermission();
        return true;
      }

      if (REQUEST_PERMISSION.equals(action)) {
        requestAudioPermission();
        return true;
      }

    } catch (Exception e) {
      e.printStackTrace();
      callbackContext.error(e.getMessage());
    }

    return false;
  }

  private boolean isRecognitionAvailable() {
    return SpeechRecognizer.isRecognitionAvailable(context);
  }

  private void startListening(String language, int matches, String prompt, final Boolean showPartial, Boolean showPopup,
                              int completeSilenceMs, int possiblyCompleteSilenceMs, int minimumLengthMs) {
    Log.d(LOG_TAG, "startListening() language: " + language + ", matches: " + matches + ", prompt: " + prompt + ", showPartial: " + showPartial + ", showPopup: " + showPopup);

    final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, matches);
    intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,
            activity.getPackageName());
    intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, showPartial);
    intent.putExtra("android.speech.extra.DICTATION_MODE", showPartial);

    // Optional Android recognition-service hints; unsupported services may ignore them.
    if (completeSilenceMs > 0) {
      intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs);
    }
    if (possiblyCompleteSilenceMs > 0) {
      intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possiblyCompleteSilenceMs);
    }
    if (minimumLengthMs > 0) {
      intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minimumLengthMs);
    }
    Log.d(LOG_TAG, "Silence hints (ms): complete=" + completeSilenceMs
            + ", possiblyComplete=" + possiblyCompleteSilenceMs + ", minimum=" + minimumLengthMs);

    if (prompt != null) {
      intent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
    }

    if (showPopup) {
      cordova.startActivityForResult(this, intent, REQUEST_CODE_SPEECH);
    } else {
      recognizer.startListening(intent);
    }
  }

  private void getSupportedLanguages() {
    if (languageDetailsChecker == null) {
      languageDetailsChecker = new LanguageDetailsChecker(callbackContext);
    }

    List<String> supportedLanguages = languageDetailsChecker.getSupportedLanguages();
    if (supportedLanguages != null) {
      JSONArray languages = new JSONArray(supportedLanguages);
      callbackContext.success(languages);
      return;
    }

    Intent detailsIntent = new Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS);
    activity.sendOrderedBroadcast(detailsIntent, null, languageDetailsChecker, null, Activity.RESULT_OK, null, null);
  }

  private void hasAudioPermission() {
    PluginResult result = new PluginResult(PluginResult.Status.OK, audioPermissionGranted(RECORD_AUDIO_PERMISSION));
    this.callbackContext.sendPluginResult(result);
  }

  private void requestAudioPermission() {
    requestPermission(RECORD_AUDIO_PERMISSION);
  }

  private boolean audioPermissionGranted(String type) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return true;
    }
    return cordova.hasPermission(type);
  }

  private void requestPermission(String type) {
    if (!audioPermissionGranted(type)) {
      cordova.requestPermission(this, 23456, type);
    } else {
      this.callbackContext.success();
    }
  }

  @Override
  public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      this.callbackContext.success();
    } else {
      this.callbackContext.error("Permission denied");
    }
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    Log.d(LOG_TAG, "onActivityResult() requestCode: " + requestCode + ", resultCode: " + resultCode);

    if (requestCode == REQUEST_CODE_SPEECH) {
      if (resultCode == Activity.RESULT_OK) {
        try {
          ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
          JSONArray jsonMatches = new JSONArray(matches);
          finishSession(currentSession, null, jsonMatches);
        } catch (Exception e) {
          e.printStackTrace();
          finishSession(currentSession, e.getMessage(), null);
        }
      } else {
        finishSession(currentSession, Integer.toString(resultCode), null);
      }
      return;
    }

    super.onActivityResult(requestCode, resultCode, data);
  }


  @Override public void onDestroy() {
    mainHandler.post(new Runnable() {
      @Override public void run() {
        if (sessionActive) finishSession(currentSession, "Cancelled", null);
        else destroyRecognizer();
      }
    });
    super.onDestroy();
  }

  private class SpeechRecognitionListener implements RecognitionListener {
    private final int listenerSession;
    SpeechRecognitionListener(int id) { listenerSession = id; }
    private boolean isCurrent() { return sessionActive && listenerSession == currentSession; }

    @Override
    public void onBeginningOfSpeech() {
      if (!isCurrent()) return;
      speechAt = SystemClock.elapsedRealtime();
      diagnostic("beginning", "");
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
      if (!isCurrent()) return;
      endAt = SystemClock.elapsedRealtime();
      diagnostic("end", "");
    }

    @Override
    public void onError(int errorCode) {
      if (!isCurrent()) return;
      String errorMessage = getErrorText(errorCode);
      Log.d(LOG_TAG, "Error: " + errorMessage);
      diagnostic("error", errorMessage);
      finishSession(listenerSession, errorMessage, null);
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }

    @Override
    public void onPartialResults(Bundle bundle) {
      if (!isCurrent()) return;
      ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
      Log.d(LOG_TAG, "SpeechRecognitionListener partialResults: " + matches);
      JSONArray matchesJSON = matches == null ? new JSONArray() : new JSONArray(matches);
      try {
        if (matches != null
                && matches.size() > 0
                        && !mLastPartialResults.equals(matchesJSON)) {
          mLastPartialResults = matchesJSON;
          PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, matchesJSON);
          pluginResult.setKeepCallback(true);
          if (sessionCallback != null) sessionCallback.sendPluginResult(pluginResult);
        }
      } catch (Exception e) {
        e.printStackTrace();
        finishSession(listenerSession, e.getMessage(), null);
      }
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
      if (!isCurrent()) return;
      readyAt = SystemClock.elapsedRealtime();
      speechAt = endAt = -1;
      Log.d(LOG_TAG, "onReadyForSpeech");
      diagnostic("ready", "");
    }

    @Override
    public void onResults(Bundle results) {
      if (!isCurrent()) return;
      ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
      Log.d(LOG_TAG, "SpeechRecognitionListener results: " + matches);
      diagnostic("results", matches == null ? "" : matches.toString());
      try {
        JSONArray jsonMatches = new JSONArray(matches);
        finishSession(listenerSession, null, jsonMatches);
      } catch (Exception e) {
        e.printStackTrace();
        finishSession(listenerSession, e.getMessage(), null);
      }
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    private String getErrorText(int errorCode) {
      String message;
      switch (errorCode) {
        case SpeechRecognizer.ERROR_AUDIO:
          message = "Audio recording error";
          break;
        case SpeechRecognizer.ERROR_CLIENT:
          message = "Client side error";
          break;
        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
          message = "Insufficient permissions";
          break;
        case SpeechRecognizer.ERROR_NETWORK:
          message = "Network error";
          break;
        case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
          message = "Network timeout";
          break;
        case SpeechRecognizer.ERROR_NO_MATCH:
          message = "No match";
          break;
        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
          message = "RecognitionService busy";
          break;
        case SpeechRecognizer.ERROR_SERVER:
          message = "error from server";
          break;
        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
          message = "No speech input";
          break;
        default:
          message = "Didn't understand, please try again.";
          break;
      }
      return message;
    }
  }

}
