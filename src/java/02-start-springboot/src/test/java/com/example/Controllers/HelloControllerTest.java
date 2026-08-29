package com.example.Controllers;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.Services.GreetingService;

@WebMvcTest(HelloController.class)
public class HelloControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    //this annotation will register a mock object of GreetingService
    //whenever contruct the HelloController, it will inject this into the controller
    //this is declarative way, it requires a global (please investigate the scope correctly, like test class scope or even for each test for isolation)

    private GreetingService greetingService;

    @Test
    void helloWithoutName_returnsDefaultGreeting() throws Exception {
        // When run a test, test runner will create an instance of controller, and pass specific mock object there. 
        // However before do that, the when will change the mock object behavior. 
        // So @MockBean must provide mock per test scope!
        when(greetingService.greet(isNull())).thenReturn("Hello, World!");

        // when perform
        mockMvc.perform(get("/api/hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("Hello, World!"));
    }

    @Test
    void helloWithName_returnsPersonalizedGreeting() throws Exception {
        when(greetingService.greet(anyString())).thenReturn("Hello, Vung");

        mockMvc.perform(get("/api/hello").param("name", "Vung"))
                .andExpect(status().isOk())
                .andExpect(content().string("Hello, Vung"));
    }
}
