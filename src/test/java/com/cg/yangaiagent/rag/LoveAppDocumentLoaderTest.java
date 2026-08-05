package com.cg.yangaiagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
class LoveAppDocumentLoaderTest {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;
    @Test
    void loadAllMarkdowns(){
        loveAppDocumentLoader.loadAllMarkdowns();
    }

    @Test
    void testLoadAllMarkdowns() {
    }

    @Test
    void loadSingleMarkdown() {
    }
}