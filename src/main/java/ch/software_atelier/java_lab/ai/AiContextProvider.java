package ch.software_atelier.java_lab.ai;

import java.util.List;

/** Add Spring beans implementing this interface to supply files, kernel facts, retrieval, etc. */
public interface AiContextProvider {
    List<CodeGeneration.ContextItem> contextFor(CodeGeneration.Request request);
}
