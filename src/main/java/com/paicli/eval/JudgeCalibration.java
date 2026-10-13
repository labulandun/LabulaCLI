package com.paicli.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure calibration math; live sampling and human labeling are intentionally external to the harness. */
public final class JudgeCalibration {

    public static final int DEFAULT_MIN_SAMPLES = 20;
    public static final int DEFAULT_TOLERANCE = 1;
    public static final double DEFAULT_MIN_AGREEMENT = 0.80;

    private JudgeCalibration() {
    }

    public static Result evaluate(List<Sample> samples) {
        return evaluate(samples, DEFAULT_MIN_SAMPLES, DEFAULT_TOLERANCE, DEFAULT_MIN_AGREEMENT);
    }

    public static Result evaluate(List<Sample> samples, int minSamples,
                                  int tolerance, double minAgreement) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("calibration samples must not be empty");
        }
        if (minSamples < 1 || tolerance < 0 || minAgreement < 0 || minAgreement > 1) {
            throw new IllegalArgumentException("invalid calibration policy");
        }

        Set<String> ids = new HashSet<>();
        int agreed = 0;
        int absoluteError = 0;
        for (Sample sample : samples) {
            if (sample == null || sample.id() == null || sample.id().isBlank()
                    || sample.humanScore() < 1 || sample.humanScore() > 5
                    || sample.judgeScore() < 1 || sample.judgeScore() > 5) {
                throw new IllegalArgumentException("invalid calibration sample: " + sample);
            }
            if (!ids.add(sample.id())) {
                throw new IllegalArgumentException("duplicate calibration sample id: " + sample.id());
            }
            int error = Math.abs(sample.humanScore() - sample.judgeScore());
            absoluteError += error;
            if (error <= tolerance) {
                agreed++;
            }
        }

        double agreement = round4((double) agreed / samples.size());
        double meanAbsoluteError = round4((double) absoluteError / samples.size());
        boolean qualified = samples.size() >= minSamples && agreement >= minAgreement;
        return new Result(samples.size(), agreed, agreement, meanAbsoluteError,
                minSamples, tolerance, minAgreement, qualified);
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public record Sample(String id, int humanScore, int judgeScore) {
    }

    public record Result(int samples,
                         int agreed,
                         double agreementRate,
                         double meanAbsoluteError,
                         int minimumSamples,
                         int tolerance,
                         double minimumAgreement,
                         boolean qualified) {
    }
}
