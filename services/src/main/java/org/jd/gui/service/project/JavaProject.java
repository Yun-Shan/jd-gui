package org.jd.gui.service.project;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.gson.*;
import com.google.gson.stream.JsonWriter;

import javax.swing.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author Yun Shan
 */
public class JavaProject {

    private final Map<String, JavaIdentifier> identifierMap = new HashMap<>();

    private final Map<String, Consumer<JavaIdentifier>> aliasChangedListener = new HashMap<>();

    private final Path projectDir;
    private FileLock fileLock;

    public JavaProject(Path projectsDir, Path filePath) {
        initProjectsDir(projectsDir);
        this.projectDir = getProjectDir(projectsDir, filePath);
        this.loadIdentifiers();
    }

    private static void initProjectsDir(Path projectsDir) {
        if (Files.notExists(projectsDir)) {
            try {
                Files.createDirectories(projectsDir);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private Path getProjectDir(Path projectsDir, Path filePath) {
        String pathStr = filePath.normalize().toAbsolutePath().toString();
        byte[] pathStrBytes = pathStr.getBytes(StandardCharsets.UTF_8);
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        byte[] digest = md5.digest(pathStrBytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(Integer.toHexString(Byte.toUnsignedInt(b)));
        }
        String hashDir = sb.toString().toLowerCase();
        Path dir = projectsDir.resolve(hashDir);

        try {
            Files.createDirectories(dir);
            FileChannel channel = FileChannel.open(dir.resolve("file_path"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            this.fileLock = channel.tryLock();
            if (this.fileLock == null) {
                JOptionPane.showMessageDialog(null, "This File has been open in another jd-gui window", null, JOptionPane.WARNING_MESSAGE);
                throw new IllegalMonitorStateException("unable to lock file");
            }
            channel.write(ByteBuffer.wrap(pathStrBytes));
            channel.force(false);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return dir;
    }

    public void unlock() {
        try {
            this.fileLock.release();
            this.fileLock.channel().close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void addIdentifier(JavaIdentifier identifier) {
        this.identifierMap.putIfAbsent(identifier.getFullName(), identifier);
    }

    public JavaIdentifier getIdentifier(String fullName) {
        return this.identifierMap.get(fullName);
    }

    public JavaIdentifier getIdentifier(String internalClassName, String memberName, String memberDescriptor) {
        return this.getIdentifier(JavaIdentifier.makeFullName(internalClassName, memberName, memberDescriptor));
    }

    public void addIdentifierAliasListener(String key, Consumer<JavaIdentifier> listener) {
        this.aliasChangedListener.put(key, listener);
    }

    public void removeIdentifierAliasListener(String key) {
        this.aliasChangedListener.remove(key);
    }

    /**
     * set alias for the identifier
     * <p>
     * if {@link JavaIdentifier#canSetAlias()} return false, this method will do nothing
     *
     * @param identifier the identifier to set alias
     * @param newAlias new alias for the identifier
     * @see JavaIdentifier#canSetAlias()
     */
    public void setAlias(JavaIdentifier identifier, String newAlias) {
        if (newAlias == null || newAlias.isEmpty()) {
            identifier.setAlias(null);
        } else {
            identifier.setAlias(newAlias);
        }
        this.aliasChangedListener.values().forEach(c -> c.accept(identifier));
    }

    public void loadIdentifiers() {
        Path path = this.projectDir.resolve("identifiers.json");
        if (Files.notExists(path)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement json = JsonParser.parseReader(reader);
            JsonArray list = json.getAsJsonArray();
            for (JsonElement e : list) {
                JsonObject obj = e.getAsJsonObject();
                JsonArray fullNameArray = obj.getAsJsonArray("full_name");
                JavaIdentifier customId;
                if (fullNameArray.size() < 3) {
                    customId = new JavaIdentifier(
                        fullNameArray.get(0).getAsString()
                    );
                } else {
                    customId = new JavaIdentifier(
                        fullNameArray.get(0).getAsString(),
                        fullNameArray.get(1).getAsString(),
                        fullNameArray.get(2).getAsString()
                    );
                }
                this.identifierMap.compute(customId.getFullName(), (key, id) -> {
                    if (id == null) {
                        id = customId;
                    }
                    JsonElement alias = obj.get("alias");
                    if (alias != null) {
                        id.setAlias(alias.getAsString());
                    }
                    JsonElement comment = obj.get("comment");
                    if (comment != null) {
                        id.setComment(comment.getAsString());
                    }
                    return id;
                });
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveIdentifiers() {
        Path path = this.projectDir.resolve("identifiers.json");
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try (JsonWriter writer = new JsonWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
            writer.beginArray();
            for (JavaIdentifier id : this.identifierMap.values()) {
                if (id.hasAlias() || id.hasComment()) {
                    writer.beginObject();

                    // full name
                    writer.name("full_name");
                    writer.beginArray();
                    for (String s : id.getFullNameArray()) {
                        if (Strings.isNullOrEmpty(s)) {
                            break;
                        }
                        writer.value(s);
                    }
                    writer.endArray();
                    // alias
                    if (id.hasAlias()) {
                        writer.name("alias").value(id.getAlias());
                    }
                    // comment
                    if (id.hasComment()) {
                        writer.name("comment").value(id.getComment());
                    }

                    writer.endObject();
                }
            }
            writer.endArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
        byte[] bytes = bout.toByteArray();
        if (bytes.length > 0) {
            try {
                Files.write(path, bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
