package com.macro.mall.search;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;

import java.util.List;

public class AiTest {

    @Test
    public void testEmbedding() {
        // 1. 加载本地模型 (第一次运行会自动下载模型文件，约 20MB)
        EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();

        // 2. 将文本转换为向量
        String text = "推荐一款适合老人的智能手机";
        Embedding embedding = embeddingModel.embed(text).content();

        // 3. 打印结果
        List<Float> vector = embedding.vectorAsList();
        System.out.println("向量维度: " + vector.size()); // 应该是 384
        System.out.println("向量前5位: " + vector.subList(0, 5));
    }
}