package io.mosip.liveness.api;

import io.mosip.liveness.eval.ThresholdSweep;
import io.mosip.liveness.services.ThresholdCalibrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Threshold calibration endpoint.
 *
 * <p>Answers "what should the configured passive threshold be" with data
 * instead of a guess: sweeps the threshold over the recorded bona-fide score
 * windows (plus attack windows — recorded or labelled proxy) using the same
 * median-window decision the live path uses, and returns the BPCER / APCER /
 * ACER table with a recommended operating point.</p>
 *
 * <p>See docs/configuration.md for how to read the result and apply it via
 * PUT /api/v1/config/{workflowType}.</p>
 */
@RestController
@RequestMapping("/api/v1/eval")
@RequiredArgsConstructor
public class CalibrationController {

    private final ThresholdCalibrationService calibration;

    /**
     * @param targetBpcer maximum acceptable bona-fide escalation rate,
     *                    0..1 (default 0.02 = 2% of genuine users challenged)
     */
    @GetMapping("/threshold-sweep")
    public ThresholdSweep.Result thresholdSweep(
            @RequestParam(name = "targetBpcer", defaultValue = "0.02") double targetBpcer) {
        if (targetBpcer < 0.0 || targetBpcer > 1.0) {
            throw new IllegalArgumentException("targetBpcer must be within [0, 1]");
        }
        return calibration.run(targetBpcer);
    }
}
