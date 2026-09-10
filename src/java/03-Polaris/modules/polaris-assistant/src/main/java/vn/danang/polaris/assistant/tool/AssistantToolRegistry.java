package vn.danang.polaris.assistant.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class AssistantToolRegistry {

    private final Map<String, AssistantTool> tools = new ConcurrentHashMap<>();

    public AssistantToolRegistry(List<AssistantTool> toolList) {
        if (toolList != null) {
            for (AssistantTool tool : toolList) {
                tools.put(tool.getName(), tool);
            }
        }
    }

    public Optional<AssistantTool> getTool(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(tools.get(name));
    }

    public List<AssistantTool> getAllTools() {
        return List.copyOf(tools.values());
    }

    public List<ToolDefinition> getDefinitions() {
        return tools.values().stream().map(AssistantTool::getDefinition).toList();
    }
}
