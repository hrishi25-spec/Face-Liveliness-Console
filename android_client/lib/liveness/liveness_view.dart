import 'dart:async';

import 'package:flutter/material.dart';

import 'liveness_view_model.dart';

/// Which card the overlay shows; `null` means the overlay is hidden.
enum _Card { status, challenge, failure }

/// One rendered frame of the overlay, mirroring the Desktop
/// `LivenessOverlayPresenter.OverlayView`: card choice, i18n keys and derived
/// numbers. Text is resolved later through [LivenessView.messageLookup].
class _OverlayFrame {
  const _OverlayFrame({
    this.card,
    this.messageKey,
    this.promptKey,
    this.feedbackKey,
    this.challengeIndex,
    this.challengeTotal,
    this.progress = 0,
    this.progressIndeterminate = false,
    this.statusProgressVisible = false,
    this.retryVisible = false,
    this.passedAccent = false,
    this.lockoutSeconds,
  })  : visible = true,
        cancelVisible = true;

  /// IDLE / nothing to render: the Desktop overlay hides itself.
  const _OverlayFrame.hidden()
      : visible = false,
        card = null,
        messageKey = null,
        promptKey = null,
        feedbackKey = null,
        challengeIndex = null,
        challengeTotal = null,
        progress = 0,
        progressIndeterminate = false,
        statusProgressVisible = false,
        retryVisible = false,
        cancelVisible = false,
        passedAccent = false,
        lockoutSeconds = null;

  final bool visible;
  final _Card? card;

  /// Status/failure message key (status and failure cards).
  final String? messageKey;

  /// Challenge headline key (challenge card).
  final String? promptKey;

  /// Live feedback line key, or `null`.
  final String? feedbackKey;

  final int? challengeIndex;
  final int? challengeTotal;

  /// Challenge progress 0..1.
  final double progress;

  /// Challenge progress bar is indeterminate (combined score not known yet).
  final bool progressIndeterminate;

  /// Indeterminate progress on the status card (passive warm-up window).
  final bool statusProgressVisible;

  /// Retry affordance (design §4 rules).
  final bool retryVisible;

  /// Cancel affordance.
  final bool cancelVisible;

  /// PASSED: the oval guide turns green.
  final bool passedAccent;

  /// LOCK_OUT cool-down, or `null`.
  final int? lockoutSeconds;
}

/// Liveness gate view (spec §11 UI/UX, §14): preview area, status, challenge
/// prompt, progress ring, retry and generic-failure messaging.
///
/// The state→UI mapping below is the Flutter twin of the Desktop
/// `LivenessOverlayPresenter`: the same cards for the same orchestrator
/// states, the same i18n keys (including the challenge-index, lockout and
/// action templates), the same retry rules (only `ATTEMPT_FAILED`,
/// `RETRY_WAIT` and recoverable `DEVICE_ERROR`; never on `TERMINAL_FAILURE`),
/// and the same lockout countdown. See `docs/ui-ux-design.md` §1/§3/§4.
///
/// All strings are resolved through [messageLookup] from the i18n keys the
/// native orchestrator emits — no raw text crosses the bridge (R5).
class LivenessView extends StatefulWidget {
  const LivenessView({
    super.key,
    required this.viewModel,
    this.messageLookup = defaultMessages,
    this.previewBuilder,
    this.onRetry,
    this.onCancel,
  });

  final LivenessViewModel viewModel;
  final Map<String, String> messageLookup;
  final WidgetBuilder? previewBuilder;
  final VoidCallback? onRetry;
  final VoidCallback? onCancel;

  /// Default English strings (design §9 defaults); deployments override via
  /// i18n. Byte-for-byte the Desktop
  /// `LivenessOverlayPresenter.DEFAULT_MESSAGES` catalogue.
  static const Map<String, String> defaultMessages = {
    'liveness.checking': 'Checking face liveness…',
    'prompt.blink': 'Please blink',
    'prompt.smile': 'Please smile',
    'prompt.turn_left': 'Please turn your head to the left',
    'prompt.turn_right': 'Please turn your head to the right',
    'prompt.look_up': 'Please look up',
    'prompt.look_down': 'Please look down',
    'prompt.look_left': 'Please look to the left',
    'prompt.look_right': 'Please look to the right',
    'prompt.look_direction': 'Please look in the indicated direction',
    'feedback.continue': 'Please continue',
    'feedback.detected': 'Action detected',
    'feedback.hold': 'Hold still',
    'liveness.success': 'Good, face captured successfully',
    'liveness.failed.try_again':
        'We could not verify face liveness. Please try again.',
    'liveness.pad.generic':
        'Face verification could not be completed. Please try again.',
    'liveness.max_retries.recovery':
        'Could not complete verification. Recovery required.',
    'liveness.device.error':
        'Device error. Please check the camera and try again.',
    'liveness.device.unavailable':
        'Biometric device not connected. Check the connection.',
    'liveness.device.disconnected':
        'Device disconnected. Reconnect and try again.',
    'liveness.camera.unavailable': 'Camera not available.',
    'liveness.cancelled': 'Verification cancelled.',
    'liveness.hint.look_camera': 'Look directly at the camera.',
    'liveness.hint.single_person': 'Only one person may be in the frame.',
    'liveness.hint.distance': 'Move a little closer.',
    'liveness.hint.lighting': 'Improve the lighting and hold still.',
    'liveness.hint.look_straight': 'Look straight at the camera.',
    'liveness.hint.hold_still': 'Hold still.',
    'liveness.action.retry': 'Retry',
    'liveness.action.cancel': 'Cancel',
    'liveness.challenge.index': 'Challenge {index} / {total}',
    'liveness.lockout.retry_in': 'Try again in {seconds}s',
  };

  /// `LivenessHint` name → its localisation key, mirroring the enum's
  /// `uiMessageKey()` on the desktop side.
  static const Map<String, String> _hintKeys = {
    'NO_FACE': 'liveness.hint.look_camera',
    'MULTIPLE_FACES': 'liveness.hint.single_person',
    'TOO_FAR': 'liveness.hint.distance',
    'TOO_DARK': 'liveness.hint.lighting',
    'BLURRY': 'liveness.hint.lighting',
    'LOOK_STRAIGHT': 'liveness.hint.look_straight',
    'HOLD_STILL': 'liveness.hint.hold_still',
  };

  @override
  State<LivenessView> createState() => _LivenessViewState();
}

class _LivenessViewState extends State<LivenessView> {
  String _resolve(String? key) {
    if (key == null || key.isEmpty) return '';
    return widget.messageLookup[key] ?? key;
  }

  /// First non-blank key wins; `null` when none is usable.
  String? _first(List<String?> keys) {
    for (final key in keys) {
      if (key != null && key.isNotEmpty) return key;
    }
    return null;
  }

  /// "Challenge 1 / 2" from the `liveness.challenge.index` template.
  String _challengeIndexText(int index, int total) => _resolve(
        'liveness.challenge.index',
      ).replaceAll('{index}', '$index').replaceAll('{total}', '$total');

  /// "Try again in 30s" from the `liveness.lockout.retry_in` template.
  String _lockoutText(int seconds) =>
      _resolve('liveness.lockout.retry_in').replaceAll('{seconds}', '$seconds');

  String? _hintKey(String? hint) =>
      hint == null ? null : LivenessView._hintKeys[hint];

  /// The challenge headline: derived from the issued challenge type, else the
  /// event key — the same precedence as the Desktop presenter.
  String _promptKeyFor(LivenessViewModel viewModel) {
    final challenge = viewModel.challenge;
    if (challenge != null && challenge.isNotEmpty) {
      final normalized = challenge
          .replaceAll('TURN_HEAD_LEFT', 'TURN_LEFT')
          .replaceAll('TURN_HEAD_RIGHT', 'TURN_RIGHT');
      return 'prompt.${normalized.toLowerCase()}';
    }
    final key = viewModel.uiMessageKey;
    if (key != null && key.startsWith('prompt.')) return key;
    return 'liveness.checking';
  }

  /// Design §1 state→UI table, plus the §4 retry/lockout rules. This is the
  /// pure mapping the Desktop presenter implements; the widget only paints it.
  _OverlayFrame _frameFor(LivenessViewModel viewModel) {
    final key = viewModel.uiMessageKey;
    switch (viewModel.state) {
      case LivenessUiState.idle:
        // Fail closed the way the Desktop overlay does: nothing rendered.
        return const _OverlayFrame.hidden();

      case LivenessUiState.initializing:
      case LivenessUiState.positioning:
        // Status card: "Look directly at the camera." / hint.
        return _OverlayFrame(
          card: _Card.status,
          messageKey: _first([
            key,
            _hintKey(viewModel.hint),
            'liveness.hint.look_camera',
          ]),
        );

      case LivenessUiState.passiveEvaluating:
        // "Checking face liveness…" + indeterminate progress.
        return _OverlayFrame(
          card: _Card.status,
          messageKey: _first([key, 'liveness.checking']),
          statusProgressVisible: true,
        );

      case LivenessUiState.challengePrompt:
        return _OverlayFrame(
          card: _Card.challenge,
          promptKey: _promptKeyFor(viewModel),
          challengeIndex: viewModel.challengeIndex,
          challengeTotal: viewModel.challengeTotal,
        );

      case LivenessUiState.challengeVerifying:
        final progress = viewModel.progress;
        return _OverlayFrame(
          card: _Card.challenge,
          promptKey: _promptKeyFor(viewModel),
          feedbackKey: _first([key, 'feedback.continue']),
          challengeIndex: viewModel.challengeIndex,
          challengeTotal: viewModel.challengeTotal,
          progress: progress ?? 0,
          progressIndeterminate: progress == null,
        );

      case LivenessUiState.challengePassed:
        // "Action detected"; the next challenge is auto-issued.
        return _OverlayFrame(
          card: _Card.status,
          messageKey: _first([key, 'feedback.detected']),
        );

      case LivenessUiState.attemptFailed:
      case LivenessUiState.retryWait:
        return _OverlayFrame(
          card: _Card.failure,
          messageKey: _first([key, 'liveness.failed.try_again']),
          retryVisible: true,
        );

      case LivenessUiState.terminalFailure:
        // Recovery guidance; no retry, lockout countdown when the client
        // surfaces one (design §4).
        return _OverlayFrame(
          card: _Card.failure,
          messageKey: 'liveness.max_retries.recovery',
          retryVisible: false,
          lockoutSeconds: viewModel.nextAction == 'LOCKOUT'
              ? viewModel.lockoutSeconds
              : null,
        );

      case LivenessUiState.deviceError:
        // Recoverable device failure: reconnect guidance, retry offered,
        // never consumes an attempt (design §4).
        return _OverlayFrame(
          card: _Card.failure,
          messageKey: _first([key, 'liveness.device.error']),
          retryVisible: true,
        );

      case LivenessUiState.passed:
        // "Good, face captured successfully"; capture enabled.
        return _OverlayFrame(
          card: _Card.status,
          messageKey: _first([key, 'liveness.success']),
          passedAccent: true,
        );

      case LivenessUiState.aborted:
        return _OverlayFrame(
          card: _Card.status,
          messageKey: _first([key, 'liveness.cancelled']),
        );
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.viewModel,
      builder: (context, _) {
        final frame = _frameFor(widget.viewModel);
        if (!frame.visible) {
          return const SizedBox.shrink();
        }
        return Stack(
          children: [
            // Preview: native renders camera frames into the Texture (R2).
            Positioned.fill(
              child: widget.previewBuilder?.call(context) ??
                  ColoredBox(color: Theme.of(context).colorScheme.surface),
            ),
            // Oval guide: 220×280, white until PASSED turns it green
            // (the Desktop overlay's ellipse and CSS accent).
            Center(
              child: IgnorePointer(
                child: Container(
                  width: 220,
                  height: 280,
                  decoration: BoxDecoration(
                    borderRadius:
                        const BorderRadius.all(Radius.elliptical(110, 140)),
                    border: Border.all(
                      color: frame.passedAccent
                          ? const Color(0xFF4CAF50)
                          : Colors.white70,
                      width: 3,
                    ),
                  ),
                ),
              ),
            ),
            // Status / prompt / failure messaging.
            Align(
              alignment: Alignment.bottomCenter,
              child: SafeArea(
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _cardFor(context, frame),
                      const SizedBox(height: 12),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          if (frame.retryVisible) ...[
                            FilledButton(
                              onPressed: widget.onRetry,
                              style: FilledButton.styleFrom(
                                backgroundColor: const Color(0xFF1976D2),
                                foregroundColor: Colors.white,
                              ),
                              child: Text(_resolve('liveness.action.retry')),
                            ),
                            const SizedBox(width: 12),
                          ],
                          if (frame.cancelVisible)
                            TextButton(
                              onPressed: widget.onCancel,
                              style: TextButton.styleFrom(
                                foregroundColor: Colors.white70,
                                side: const BorderSide(
                                  color: Colors.white54,
                                ),
                              ),
                              child: Text(_resolve('liveness.action.cancel')),
                            ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _cardFor(BuildContext context, _OverlayFrame frame) {
    switch (frame.card) {
      case _Card.challenge:
        return _challengeCard(context, frame);
      case _Card.failure:
        return _failureCard(context, frame);
      case _Card.status:
      case null:
        return _statusCard(context, frame);
    }
  }

  Widget _statusCard(BuildContext context, _OverlayFrame frame) {
    return _card(
      context,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (frame.statusProgressVisible)
            const Padding(
              padding: EdgeInsets.only(bottom: 8),
              child: SizedBox(
                width: 160,
                child: LinearProgressIndicator(),
              ),
            ),
          Text(_resolve(frame.messageKey), textAlign: TextAlign.center),
        ],
      ),
    );
  }

  Widget _challengeCard(BuildContext context, _OverlayFrame frame) {
    final index = frame.challengeIndex;
    final total = frame.challengeTotal;
    final numbered = index != null && total != null;
    return _card(
      context,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (numbered)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                _challengeIndexText(index, total),
                style: Theme.of(context)
                    .textTheme
                    .labelSmall
                    ?.copyWith(color: Colors.white70),
              ),
            ),
          Text(
            _resolve(frame.promptKey),
            style: Theme.of(context)
                .textTheme
                .headlineSmall
                ?.copyWith(fontWeight: FontWeight.bold),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 12),
          SizedBox(
            width: 160,
            child: frame.progressIndeterminate
                ? const LinearProgressIndicator()
                : LinearProgressIndicator(value: frame.progress),
          ),
          if (frame.feedbackKey != null) ...[
            const SizedBox(height: 8),
            Text(
              _resolve(frame.feedbackKey),
              style: Theme.of(context).textTheme.labelMedium,
            ),
          ],
        ],
      ),
    );
  }

  Widget _failureCard(BuildContext context, _OverlayFrame frame) {
    final lockout = frame.lockoutSeconds;
    return _card(
      context,
      tone: const Color(0xFF8C1414),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(_resolve(frame.messageKey), textAlign: TextAlign.center),
          if (lockout != null && lockout > 0)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: _LockoutCountdown(
                seconds: lockout,
                label: _lockoutText,
              ),
            ),
        ],
      ),
    );
  }

  Widget _card(BuildContext context, {required Widget child, Color? tone}) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: (tone ?? const Color(0xFF141414)).withOpacity(0.85),
        borderRadius: BorderRadius.circular(14),
        boxShadow: const [
          BoxShadow(
            color: Color(0x59000000),
            blurRadius: 12,
            offset: Offset(0, 3),
          ),
        ],
      ),
      child: DefaultTextStyle(
        style: Theme.of(context)
                .textTheme
                .bodyMedium
                ?.copyWith(color: Colors.white) ??
            const TextStyle(color: Colors.white),
        child: child,
      ),
    );
  }
}

/// LOCK_OUT countdown ("Try again in Ns"), ticking once a second and hiding at
/// zero — the same behaviour as the Desktop overlay's countdown.
class _LockoutCountdown extends StatefulWidget {
  const _LockoutCountdown({required this.seconds, required this.label});

  final int seconds;
  final String Function(int seconds) label;

  @override
  State<_LockoutCountdown> createState() => _LockoutCountdownState();
}

class _LockoutCountdownState extends State<_LockoutCountdown> {
  Timer? _timer;
  late int _remaining = widget.seconds;

  @override
  void initState() {
    super.initState();
    _start();
  }

  @override
  void didUpdateWidget(covariant _LockoutCountdown oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.seconds != widget.seconds) {
      _remaining = widget.seconds;
      _start();
    }
  }

  void _start() {
    _timer?.cancel();
    _timer = null;
    if (_remaining <= 0) return;
    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      setState(() => _remaining--);
      if (_remaining <= 0) timer.cancel();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_remaining <= 0) return const SizedBox.shrink();
    return Text(
      widget.label(_remaining),
      style: Theme.of(context)
          .textTheme
          .labelMedium
          ?.copyWith(color: const Color(0xFFFFD54F)),
    );
  }
}
