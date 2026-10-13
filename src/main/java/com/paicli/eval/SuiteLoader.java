package com.paicli.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 扫描 eval/suites/<suite>/<case-id>/case.json，加载为 CaseSpec 列表。 */
public final class SuiteLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SuiteLoader() {
    }

    /**
     * @param evalHome   eval 根目录（含 suites/ 子目录）
     * @param suites     要加载的 suite 名列表
     * @param caseFilter 非空时只保留 id 等于该值的 case
     */
    public static List<CaseSpec> load(Path evalHome, List<String> suites, String caseFilter) throws IOException {
        List<CaseSpec> cases = new ArrayList<>();
        for (String suite : suites) {
            Path suiteDir = evalHome.resolve("suites").resolve(suite);
            if (!Files.isDirectory(suiteDir)) {
                throw new IllegalArgumentException("suite 目录不存在: " + suiteDir);
            }
            try (Stream<Path> children = Files.list(suiteDir)) {
                List<Path> caseDirs = children.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
                for (Path caseDir : caseDirs) {
                    Path caseFile = caseDir.resolve("case.json");
                    if (!Files.isRegularFile(caseFile)) {
                        continue;
                    }
                    cases.add(parseCaseFile(suite, caseDir, caseFile));
                }
            }
        }
        if (caseFilter != null && !caseFilter.isBlank()) {
            cases = cases.stream().filter(c -> c.id().equals(caseFilter)).toList();
        }
        return cases;
    }

    private static CaseSpec parseCaseFile(String suite, Path caseDir, Path caseFile) {
        try {
            JsonNode root = MAPPER.readTree(caseFile.toFile());
            return CaseSpec.parse(caseDir, suite, root);
        } catch (IOException e) {
            throw new UncheckedIOException("case.json 解析失败: " + caseFile, e);
        }
    }
}
