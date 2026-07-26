package com.paicli.eval;

import java.nio.file.Path;
import java.util.List;

/** 评分器：只读 artifact 与 workspace，产出若干 Score，不修改工作区业务文件。 */
public interface Grader {

    List<Score> grade(CaseSpec spec, RunArtifact artifact, Path workspace) throws Exception;
}
