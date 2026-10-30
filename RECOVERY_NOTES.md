Native SpeechRecognizer lifecycle recovery:
- One recognizer instance per session, created/destroyed on main thread.
- Reject overlapping native starts.
- Bind listener and result callback to a session ID.
- Stop waits for terminal callback, with 2500 ms forced cleanup.
- Diagnostic events include sessionId, recognizerId, timestampMs.
- App uses exponential backoff on ERROR_CLIENT.
- Thresholds remain optional hints.
