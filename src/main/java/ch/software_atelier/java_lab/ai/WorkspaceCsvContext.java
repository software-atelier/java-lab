package ch.software_atelier.java_lab.ai;

import ch.software_atelier.java_lab.CsvStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public class WorkspaceCsvContext implements AiContextProvider {
    private final CsvStore csv;
    private final ObjectMapper mapper;
    public WorkspaceCsvContext(CsvStore csv, ObjectMapper mapper) { this.csv = csv; this.mapper = mapper; }
    @Override public List<CodeGeneration.ContextItem> contextFor(CodeGeneration.Request request) {
        try {
            var catalog = csv.catalog();
            return List.of(new CodeGeneration.ContextItem("workspace-csv", "Saved workspace CSV files", mapper.writeValueAsString(catalog),
                Map.of("pathsRelativeTo", "configured data directory (kernel working directory)", "firstRowIsAssumedHeader", true,
                    "dataRowsIncluded", false, "catalogTruncated", catalog.truncated(), "savedFilesOnly", true)));
        } catch (IOException e) {
            return List.of(new CodeGeneration.ContextItem("workspace-csv", "Workspace CSV catalog unavailable",
                "CSV metadata could not be read. Do not assume any file paths or column names.", Map.of("available", false)));
        }
    }
}
