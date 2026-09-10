package vn.danang.polaris.assistant.model;

import java.util.List;

import vn.danang.polaris.assistant.tool.AssistantTool;

public interface AssistantModelClient {

    void streamChat(SessionContext context, List<AssistantTool> tools, ModelStreamListener listener);
}
