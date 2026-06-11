package io.agentscope.router.business.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.router.business.demo.DemoProperties;
import io.agentscope.router.common.exception.BizException;
import io.agentscope.router.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Demo-only {@code @Tool} bean used by the {@code fileCollectorAgent} in the
 * AI-music multi-agent demo.
 *
 * <p>This class used to host two tools:
 * <ol>
 *   <li>{@code write_promo_copy} — a sub-LLM call wrapped in a tool. Removed
 *       in the multi-agent refactor: copy generation is now done by the
 *       dedicated {@code copywriterAgent} ReAct agent, which is a first-class
 *       participant in the {@code MsgHub}, not a tool call.</li>
 *   <li>{@code download_music_file} — a local file IO call. Present;
 *       consumed by {@code fileCollectorAgent} to move a generated MP3 from
 *       the router's temp dir to a user-visible destination path.</li>
 * </ol>
 *
 * <p><b>Why a local copy, not a download.</b> {@code MediaTools.text_to_music}
 * invokes the synchronous {@code music-01} API and persists the resulting MP3
 * to a temp path under {@code agentscope.multimodal.outputDir} (see
 * {@code MultimodalService.persistAudio}). The {@code fileCollectorAgent}
 * receives that absolute temp path via the {@code MsgHub} and uses this tool
 * to copy it to the user-specified destination — no HTTP fetch is involved.
 */
@Component
public class PromoDemoTools {

    private static final Logger log = LoggerFactory.getLogger(PromoDemoTools.class);

    private final DemoProperties demoProperties;

    public PromoDemoTools(DemoProperties demoProperties) {
        this.demoProperties = demoProperties;
    }

    /**
     * Copy a generated music file from the router's temp dir to a
     * user-visible destination path. {@code audio_path} is the absolute temp
     * path that {@code text_to_music} returned; {@code save_to} is the final
     * destination (typically {@code ai-music-{duration}s.mp3} under
     * {@code agentscope.demo.ai-promo.outputDir}). Returns the absolute
     * destination path on success.
     */
    @Tool(name = "download_music_file",
          description = "Copy a generated music file from the router's temp dir to a user-visible "
                  + "destination path. Returns the destination absolute path.")
    public String downloadMusicFile(
            @ToolParam(name = "audio_path", required = true,
                    description = "Absolute path returned by text_to_music (router temp MP3).")
            String audioPath,
            @ToolParam(name = "save_to", required = true,
                    description = "Destination absolute path including filename, "
                            + "e.g. /abs/dir/ai-music-10s.mp3. "
                            + "Parent directories will be created.")
            String saveTo) {
        try {
            Path src = Path.of(audioPath);
            Path dst = Path.of(saveTo);
            if (dst.getParent() != null) {
                Files.createDirectories(dst.getParent());
            }
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            log.info("[tool:download_music_file] copied src={} dst={}", src, dst);
            return dst.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "Failed to copy music file: " + e.getMessage());
        }
    }

    // Used by AiPromoDemoService to build a deterministic save path from yml.
    public DemoProperties demoProperties() {
        return demoProperties;
    }
}
