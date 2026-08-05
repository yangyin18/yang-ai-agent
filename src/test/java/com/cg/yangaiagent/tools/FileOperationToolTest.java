package com.cg.yangaiagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class FileOperationToolTest {

    @Test
    void readFile() {
        FileOperationTool fileOperationTool = new FileOperationTool();
        String filename = "编程.txt";
        String content = "love";
        String res = fileOperationTool.readFile(filename);
        System.out.println(res);
        Assertions.assertNotNull(res);
    }

    @Test
    void writeFile() {
        FileOperationTool fileOperationTool = new FileOperationTool();
        String filename = "编程.txt";
        String content = "love";
        String res = fileOperationTool.writeFile(filename,content);
        Assertions.assertNotNull(res);
    }
}