package com.cg.yangaiagent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
public class TerminalToolTest {

    @Autowired
    private TerminalTool terminalTool;

    @Test
    void testEcho() {
        String result = terminalTool.executeCommand("echo Hello World");
        System.out.println(result);
    }

    @Test
    void testPing() {
        String result = terminalTool.executeCommand("ping baidu.com -n 4");
        System.out.println(result);
    }

    @Test
    void testListFiles() {
        String result = terminalTool.executeCommand("ls -la");
        System.out.println(result);
    }
}