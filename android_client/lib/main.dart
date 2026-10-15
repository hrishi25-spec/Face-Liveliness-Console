import 'package:flutter/material.dart';

import 'liveness/liveness_view.dart';
import 'liveness/liveness_view_model.dart';

void main() => runApp(const LivenessClientApp());

/// Build harness for this integration scaffold. The downstream MOSIP host
/// supplies the native API, camera preview, and session collaborators.
class LivenessClientApp extends StatelessWidget {
  const LivenessClientApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MOSIP liveness integration',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF176B87)),
        useMaterial3: true,
      ),
      home: const LivenessClientHome(),
    );
  }
}

class LivenessClientHome extends StatefulWidget {
  const LivenessClientHome({super.key});

  @override
  State<LivenessClientHome> createState() => _LivenessClientHomeState();
}

class _LivenessClientHomeState extends State<LivenessClientHome> {
  late final LivenessViewModel _viewModel = LivenessViewModel();
  bool _started = false;

  @override
  void dispose() {
    _viewModel.dispose();
    super.dispose();
  }

  Future<void> _start() async {
    setState(() => _started = true);
    await _viewModel.startSession(LivenessRole.resident);
  }

  Future<void> _cancel() async {
    await _viewModel.cancelSession();
    if (mounted) setState(() => _started = false);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('MOSIP liveness integration')),
      body: _started
          ? LivenessView(
              viewModel: _viewModel,
              onRetry: _start,
              onCancel: _cancel,
            )
          : Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text(
                      'Build harness only. The MOSIP host must register the '
                      'native liveness API and camera source before sessions '
                      'can run.',
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 16),
                    FilledButton(
                      onPressed: _start,
                      child: const Text('Start liveness gate'),
                    ),
                  ],
                ),
              ),
            ),
    );
  }
}
