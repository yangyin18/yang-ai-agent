package com.cg.yangaiagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于文件的 ChatMemory 实现（每个会话一个文件）
 * 注意：此类不是 Spring Bean，每次使用需要手动创建实例。
 */
public class FileBasedChatMemory implements ChatMemory {

    private final String conversationId;
    private final File memoryFile;

    // Kryo 实例（线程安全）
    private static final ThreadLocal<Kryo> kryoThreadLocal = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());

        // 注册 Spring AI 的消息类，避免序列化失败
        kryo.register(ArrayList.class);
        kryo.register(UserMessage.class);
        kryo.register(AssistantMessage.class);
        // 由于 Message 是接口，需要注册其已知实现类
        // 如果还有其他实现类，需一并注册
        return kryo;
    });

    /**
     * 构造函数
     * @param baseDir        存储目录
     * @param conversationId 会话ID（文件名）
     */
    public FileBasedChatMemory(String baseDir, String conversationId) {
        this.conversationId = conversationId;
        File dir = new File(baseDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.memoryFile = new File(dir, conversationId + ".dat");
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (!this.conversationId.equals(conversationId)) {
            throw new IllegalArgumentException("ConversationId mismatch");
        }
        List<Message> existing = readFromFile();
        existing.addAll(messages);
        writeToFile(existing);
    }

    @Override
    public List<Message> get(String conversationId) {
        if (!this.conversationId.equals(conversationId)) {
            return new ArrayList<>();
        }
        return readFromFile();
    }



    @Override
    public void clear(String conversationId) {
        if (this.conversationId.equals(conversationId) && memoryFile.exists()) {
            memoryFile.delete();
        }
    }

    private List<Message> readFromFile() {
        if (!memoryFile.exists() || memoryFile.length() == 0) {
            return new ArrayList<>();
        }
        try (Input input = new Input(new FileInputStream(memoryFile))) {
            Kryo kryo = kryoThreadLocal.get();
            return kryo.readObject(input, ArrayList.class);
        } catch (IOException e) {
            throw new RuntimeException("读取聊天记忆失败: " + memoryFile.getAbsolutePath(), e);
        }
    }

    private void writeToFile(List<Message> messages) {
        try (Output output = new Output(new FileOutputStream(memoryFile))) {
            Kryo kryo = kryoThreadLocal.get();
            kryo.writeObject(output, messages);
        } catch (IOException e) {
            throw new RuntimeException("写入聊天记忆失败: " + memoryFile.getAbsolutePath(), e);
        }
    }
}