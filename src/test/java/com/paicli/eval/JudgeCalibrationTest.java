package com.paicli.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeCalibrationTest {

    @Test
    void qualifiesAtTwentySamplesAndEightyPercentAgreement() {
        JudgeCalibration.Result result = JudgeCalibration.evaluate(samples(16, 4));

        assertTrue(result.qualified());
        assertTrue(result.agreementRate() >= 0.80);
    }

    @Test
    void rejectsInsufficientOrLowAgreementSamples() {
        assertFalse(JudgeCalibration.evaluate(samples(15, 4)).qualified());
        assertFalse(JudgeCalibration.evaluate(samples(15, 5)).qualified());
    }

    @Test
    void rejectsDuplicateIdsAndInvalidScores() {
        List<JudgeCalibration.Sample> duplicate = List.of(
                new JudgeCalibration.Sample("same", 3, 3),
                new JudgeCalibration.Sample("same", 4, 4));
        assertThrows(IllegalArgumentException.class,
                () -> JudgeCalibration.evaluate(duplicate, 2, 1, 0.8));
        assertThrows(IllegalArgumentException.class,
                () -> JudgeCalibration.evaluate(
                        List.of(new JudgeCalibration.Sample("bad", 0, 3)), 1, 1, 0.8));
    }

    private static List<JudgeCalibration.Sample> samples(int agreed, int disagreed) {
        List<JudgeCalibration.Sample> samples = new ArrayList<>();
        for (int i = 0; i < agreed; i++) {
            samples.add(new JudgeCalibration.Sample("agree-" + i, 4, i % 2 == 0 ? 4 : 3));
        }
        for (int i = 0; i < disagreed; i++) {
            samples.add(new JudgeCalibration.Sample("disagree-" + i, 5, 2));
        }
        return samples;
    }
}
