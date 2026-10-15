import 'package:flutter/foundation.dart' show kDebugMode;
import 'package:flutter/material.dart';

import 'liveness_view_model.dart';

/// Liveness gate view (spec §11 UI/UX, §14): preview area, status, challenge
/// prompt, progress ring, retry and generic-failure messaging. Mirrors the
/// challenge overlay contract from docs/client-integration-guide.md §4.
///
/// All strings are resolved through [messageLookup] from the i18n keys the
/// native orchestrator emits — no raw text crosses the bridge (R5).
class LivenessView extends StatelessWidget {
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

  /// Default English strings (spec §9 defaults); deployments override via i18n.
  static const Map<String, String> defaultMessages = {
    'liveness.checking': 'Checking face liveness…',
    'prompt.blink': 'Please blink',
    'prompt.smile': 'Please smile',
    'prompt.turn_left': 'Please turn your head to the left',
    'prompt.turn_right': 'Please turn your head to the right',
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
    'liveness.camera.unavailable': 'Camera not available.',
    'liveness.hint.look_camera': 'Look directly at the camera.',
    'liveness.hint.single_person': 'Only one person may be in the frame.',
    'liveness.hint.distance': 'Move a little closer.',
    'liveness.hint.lighting': 'Improve the lighting and hold still.',
    'liveness.hint.look_straight': 'Look straight at the camera.',
    'liveness.hint.hold_still': 'Hold still.',
  };

  String _msg(String? key, {String fallback = ''}) =>
      key == null ? fallback : (messageLookup[key] ?? key);

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: viewModel,
      builder: (context, _) {
        final state = viewModel.state;
        return Stack(
          children: [
            // Preview: native renders camera frames into the Texture (R2).
            Positioned.fill(
              child: previewBuilder?.call(context) ??
                  ColoredBox(color: Theme.of(context).colorScheme.surface),
            ),
            // Oval guide overlay.
            Center(
              child: IgnorePointer(
                child: Container(
                  width: 220,
                  height: 280,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: state == LivenessUiState.passed
                          ? Colors.greenAccent
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
                      if (state == LivenessUiState.challengePrompt ||
                          state == LivenessUiState.challengeVerifying)
                        _challengeCard(context, state)
                      else if (state == LivenessUiState.attemptFailed ||
                          state == LivenessUiState.terminalFailure ||
                          state == LivenessUiState.deviceError)
                        _failureCard(context, state)
                      else
                        _statusCard(context, state),
                      const SizedBox(height: 12),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          if (state == LivenessUiState.attemptFailed ||
                              state == LivenessUiState.deviceError)
                            FilledButton(
                              onPressed: onRetry,
                              child: Text(_msg('feedback.continue')),
                            ),
                          const SizedBox(width: 12),
                          TextButton(
                            onPressed: onCancel,
                            child: const Text('Cancel'),
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

  Widget _statusCard(BuildContext context, LivenessUiState state) {
    final key = switch (state) {
      LivenessUiState.positioning => viewModel.hint != null
          ? _hintKey(viewModel.hint!)
          : 'liveness.hint.look_camera',
      LivenessUiState.challengePassed => 'feedback.detected',
      LivenessUiState.passed => 'liveness.success',
      _ => viewModel.uiMessageKey ?? 'liveness.checking',
    };
    return _card(context, child: Text(_msg(key), textAlign: TextAlign.center));
  }

  Widget _challengeCard(BuildContext context, LivenessUiState state) {
    final promptKey = viewModel.uiMessageKey ?? 'liveness.checking';
    final feedback = switch (state) {
      LivenessUiState.challengeVerifying => 'feedback.continue',
      LivenessUiState.challengePassed => 'feedback.detected',
      _ => null,
    };
    return _card(
      context,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (viewModel.challengeTotal != null &&
              viewModel.challengeIndex != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                'Challenge '
                '${viewModel.challengeIndex} / ${viewModel.challengeTotal}',
                style: Theme.of(context).textTheme.labelSmall,
              ),
            ),
          Text(_msg(promptKey),
              style: Theme.of(context).textTheme.headlineSmall,
              textAlign: TextAlign.center),
          const SizedBox(height: 12),
          SizedBox(
            width: 160,
            child: LinearProgressIndicator(
              value: viewModel.progress ??
                  (state == LivenessUiState.challengeVerifying ? null : 0),
            ),
          ),
          if (feedback != null) ...[
            const SizedBox(height: 8),
            Text(_msg(feedback),
                style: Theme.of(context).textTheme.labelMedium),
          ],
        ],
      ),
    );
  }

  Widget _failureCard(BuildContext context, LivenessUiState state) {
    final isPad = viewModel.uiMessageKey == 'liveness.pad.generic';
    final key = state == LivenessUiState.terminalFailure
        ? 'liveness.max_retries.recovery'
        : (viewModel.uiMessageKey ??
            (state == LivenessUiState.deviceError
                ? 'liveness.device.error'
                : 'liveness.failed.try_again'));
    return _card(
      context,
      tone: Colors.redAccent,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(_msg(key), textAlign: TextAlign.center),
          if (viewModel.failCategory == 'MAX_RETRIES' &&
              viewModel.nextAction == 'LOCKOUT')
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                'Try again in ${viewModel.lockoutSeconds ?? 0}s',
                style: Theme.of(context).textTheme.labelMedium,
              ),
            ),
          // PAD stays generic — no detection detail in the UI (R5); the note
          // below is a QA aid only in debug builds.
          if (isPad && kDebugMode)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(
                '(debug) generic PAD message shown; detail in audit only',
                style: Theme.of(context).textTheme.labelSmall,
              ),
            ),
        ],
      ),
    );
  }

  String _hintKey(String hint) => switch (hint) {
        'NO_FACE' => 'liveness.hint.look_camera',
        'MULTIPLE_FACES' => 'liveness.hint.single_person',
        'TOO_FAR' => 'liveness.hint.distance',
        'TOO_DARK' || 'BLURRY' => 'liveness.hint.lighting',
        'LOOK_STRAIGHT' => 'liveness.hint.look_straight',
        'HOLD_STILL' => 'liveness.hint.hold_still',
        _ => 'liveness.checking',
      };

  Widget _card(BuildContext context, {required Widget child, Color? tone}) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color:
            (tone ?? Theme.of(context).colorScheme.surface).withOpacity(0.92),
        borderRadius: BorderRadius.circular(14),
      ),
      child: DefaultTextStyle(
        style: Theme.of(context)
                .textTheme
                .bodyMedium
                ?.copyWith(color: tone == null ? null : Colors.white) ??
            const TextStyle(),
        child: child,
      ),
    );
  }
}
