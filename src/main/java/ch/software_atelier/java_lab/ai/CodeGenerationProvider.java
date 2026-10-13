package ch.software_atelier.java_lab.ai;

import java.util.List;

/** Transport boundary: additional model providers can implement this contract. */
public interface CodeGenerationProvider {
    String name();
    boolean configured();
    String model();
    CodeGeneration.Result generate(List<CodeGeneration.Message> messages);
}
