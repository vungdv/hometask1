package vn.danang.polaris.assistant.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ChatViewController {

    @GetMapping({"/chat", "/chat/"})
    public String chatView() {
        return "forward:/chat/index.html";
    }
}
