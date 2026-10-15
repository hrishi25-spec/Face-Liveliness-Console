import 'package:pigeon/pigeon.dart';

/// Pigeon bridge for the Android liveness gate (orchestration spec §7).
///
/// Regenerate the host/flutter code with:
///   ./tool/generate_pigeon.sh
///
/// Rules encoded here (spec R1–R5):
///   * Frames NEVER cross this channel (R2) — preview is a Flutter Texture,
///     analysis stays native. Only IDs, states and i18n keys travel.
///   * UI receives generic messages: no raw scores, no PAD detail (R5).
///   * The user never selects a challenge (R3) — the native orchestrator does.
@HostApi()
abstract class LivenessHostApi {
  /// Start a gate for a role (RESIDENT | OPERATOR | SUPERVISOR).
  /// Returns the session id and the preview Texture id (R2).
  @async
  LivenessStartResult startSession(String role, String? userId);

  /// User cancel / lifecycle teardown (any state -> ABORTED, not an attempt).
  void cancelSession();

  /// True while the most recent PASSED gate is inside its validity window.
  /// Call before triggering the downstream capture/auth step (spec §8).
  bool isGateValid();

  /// Diagnostic snapshot (diagnostic mode only; no pixels).
  Map<String, String> diagnosticsSnapshot();
}

@FlutterApi()
abstract class LivenessFlutterApi {
  /// Per-state UI event; see [LivenessStateEvent.state] + [uiMessageKey].
  void onState(LivenessStateEvent event);

  /// Terminal outcome (PASSED | TERMINAL_FAILURE | ABORTED).
  void onFinal(LivenessFinalResult result);
}

class LivenessStartResult {
  String? sessionId;
  int? previewTextureId;
  String? errorCode;
}

class LivenessStateEvent {
  String? sessionId;
  String? state; // enum name from the orchestrator state machine
  String? challenge; // current challenge type name, if prompting
  String? hint; // LivenessHint name, if a positioning hint is active
  String? uiMessageKey; // localisation key, NOT raw text (R5)
  int? attemptsUsed;
  int? attemptsMax;
  int? challengeIndex; // 1-based
  int? challengeTotal;
  double? progress; // 0..1 UI ring (never the raw score)
  String? failCategory; // GENERIC | DEVICE | MAX_RETRIES
}

class LivenessFinalResult {
  String? sessionId;
  String? outcome; // PASSED | TERMINAL_FAILURE | ABORTED
  String? nextAction; // PROCEED | BLOCK | FALLBACK | LOCKOUT | EXCEPTION
  int? lockoutSeconds;
  int? validForSeconds;
}
