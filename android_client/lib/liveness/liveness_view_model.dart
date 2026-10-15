import 'package:flutter/foundation.dart';

import '../pigeon/liveness.dart';

/// UI states mirrored from the native orchestrator (spec §4). The Dart layer
/// renders state only — every decision stays native.
enum LivenessUiState {
  idle,
  initializing,
  positioning,
  passiveEvaluating,
  challengePrompt,
  challengeVerifying,
  challengePassed,
  attemptFailed,
  retryWait,
  passed,
  terminalFailure,
  deviceError,
  aborted,
}

LivenessUiState uiStateFrom(String? name) => LivenessUiState.values.firstWhere(
      (state) => state.name.toUpperCase() == (name ?? '').toUpperCase(),
      orElse: () => LivenessUiState.deviceError,
    );

/// Small host seam for the generated Pigeon client and the no-host harness.
abstract class LivenessHost {
  Future<LivenessStartResult> startSession(String role, String? userId);
  Future<void> cancelSession();
  Future<bool> isGateValid();
}

class PigeonLivenessHost implements LivenessHost {
  PigeonLivenessHost({LivenessHostApi? api}) : _api = api ?? LivenessHostApi();

  final LivenessHostApi _api;

  @override
  Future<LivenessStartResult> startSession(String role, String? userId) =>
      _api.startSession(role, userId);

  @override
  Future<void> cancelSession() => _api.cancelSession();

  @override
  Future<bool> isGateValid() => _api.isGateValid();
}

/// ViewModel for the resident/operator/supervisor liveness gate. It sends
/// start/cancel commands and receives only generic events with i18n keys (R5).
class LivenessViewModel extends ChangeNotifier {
  LivenessViewModel({LivenessHost? hostApi})
      : _hostApi = hostApi ?? _UnavailableLivenessHost.instance;

  final LivenessHost _hostApi;

  LivenessUiState _state = LivenessUiState.idle;
  String? _uiMessageKey;
  String? _hint;
  String? _challenge;
  int? _challengeIndex;
  int? _challengeTotal;
  double? _progress;
  int _attemptsUsed = 0;
  int _attemptsMax = 3;
  String? _failCategory;
  int? _lockoutSeconds;
  String? _nextAction;
  bool _captureEnabled = false;

  LivenessUiState get state => _state;
  String? get uiMessageKey => _uiMessageKey;
  String? get hint => _hint;
  String? get challenge => _challenge;
  int? get challengeIndex => _challengeIndex;
  int? get challengeTotal => _challengeTotal;
  double? get progress => _progress;
  int get attemptsUsed => _attemptsUsed;
  int get attemptsMax => _attemptsMax;
  String? get failCategory => _failCategory;
  int? get lockoutSeconds => _lockoutSeconds;
  String? get nextAction => _nextAction;

  /// Downstream capture/auth must re-check gate validity before proceeding.
  bool get captureEnabled => _captureEnabled;

  /// Register the generated callback handler with the Flutter binary messenger.
  /// The MOSIP host calls this once for each active liveness screen.
  void bindFlutterApi() {
    LivenessFlutterApi.setup(_ViewModelFlutterApi(this));
  }

  Future<void> startSession(LivenessRole role, {String? userId}) async {
    _reset();
    _state = LivenessUiState.initializing;
    notifyListeners();
    try {
      final result =
          await _hostApi.startSession(role.name.toUpperCase(), userId);
      if (result.errorCode != null) {
        _state = LivenessUiState.deviceError;
        _uiMessageKey = 'liveness.device.error';
        notifyListeners();
      }
    } catch (_) {
      _state = LivenessUiState.deviceError;
      _uiMessageKey = 'liveness.device.error';
      notifyListeners();
    }
    // Subsequent updates arrive via onState/onFinal through the FlutterApi.
  }

  Future<void> cancelSession() => _hostApi.cancelSession();

  /// Gate consumption (spec §8): refuse if the validity window has elapsed.
  Future<bool> ensureGateValidForCapture() => _hostApi.isGateValid();

  void _handleStateEvent(LivenessStateEvent event) {
    _state = uiStateFrom(event.state);
    _uiMessageKey = event.uiMessageKey;
    _hint = event.hint;
    _challenge = event.challenge;
    _challengeIndex = event.challengeIndex;
    _challengeTotal = event.challengeTotal;
    _progress = event.progress;
    _attemptsUsed = event.attemptsUsed ?? _attemptsUsed;
    _attemptsMax = event.attemptsMax ?? _attemptsMax;
    _failCategory = event.failCategory;
    if (_state == LivenessUiState.passed) {
      _captureEnabled = true;
    } else if (_state == LivenessUiState.deviceError ||
        _state == LivenessUiState.terminalFailure) {
      _captureEnabled = false;
    }
    notifyListeners();
  }

  void _handleFinalResult(LivenessFinalResult result) {
    _nextAction = result.nextAction;
    _lockoutSeconds = result.lockoutSeconds;
    switch (result.outcome) {
      case 'PASSED':
        _state = LivenessUiState.passed;
        _captureEnabled = true;
        break;
      case 'ABORTED':
        _state = LivenessUiState.aborted;
        _captureEnabled = false;
        break;
      default:
        _state = LivenessUiState.terminalFailure;
        _captureEnabled = false;
    }
    notifyListeners();
  }

  void _reset() {
    _state = LivenessUiState.idle;
    _uiMessageKey = null;
    _hint = null;
    _challenge = null;
    _challengeIndex = null;
    _challengeTotal = null;
    _progress = null;
    _attemptsUsed = 0;
    _attemptsMax = 3;
    _failCategory = null;
    _lockoutSeconds = null;
    _nextAction = null;
    _captureEnabled = false;
  }
}

enum LivenessRole { resident, operator, supervisor }

class _ViewModelFlutterApi extends LivenessFlutterApi {
  _ViewModelFlutterApi(this._viewModel);

  final LivenessViewModel _viewModel;

  @override
  void onState(LivenessStateEvent event) => _viewModel._handleStateEvent(event);

  @override
  void onFinal(LivenessFinalResult result) =>
      _viewModel._handleFinalResult(result);
}

class _UnavailableLivenessHost implements LivenessHost {
  const _UnavailableLivenessHost._();

  static const instance = _UnavailableLivenessHost._();

  @override
  Future<LivenessStartResult> startSession(String role, String? userId) async =>
      LivenessStartResult(errorCode: 'DEVICE_UNAVAILABLE');

  @override
  Future<void> cancelSession() async {}

  @override
  Future<bool> isGateValid() async => false;
}
