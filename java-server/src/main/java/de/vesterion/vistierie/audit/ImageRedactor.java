package de.vesterion.vistierie.audit;

import de.vesterion.vistierie.provider.ProviderRequest;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Redacts vision image blobs in a ProviderRequest before audit serialization.
 * Anthropic vision messages have content arrays with image blocks shaped:
 *   {"type":"image","source":{"type":"base64","media_type":"image/png","data":"<base64>"}}
 * After redaction:
 *   {"type":"image_redacted","media_type":"image/png","sha256":"...","bytes":N}
 */
@Component
public class ImageRedactor {

    @SuppressWarnings("unchecked")
    public ProviderRequest redact(ProviderRequest req) {
        if (req.messages() == null) return req;
        var newMessages = new ArrayList<Map<String, Object>>(req.messages().size());
        for (var msg : req.messages()) {
            var copy = new HashMap<>(msg);
            var content = msg.get("content");
            if (content instanceof List<?> blocks) {
                var newBlocks = new ArrayList<Object>(blocks.size());
                for (var block : blocks) {
                    if (block instanceof Map<?, ?> bm && "image".equals(bm.get("type"))) {
                        newBlocks.add(redactImageBlock((Map<String, Object>) bm));
                    } else {
                        newBlocks.add(block);
                    }
                }
                copy.put("content", newBlocks);
            }
            newMessages.add(copy);
        }
        return new ProviderRequest(req.model(), req.maxTokens(), req.temperature(),
                req.system(), newMessages, req.tools(), req.toolChoice(), req.metadata());
    }

    private Map<String, Object> redactImageBlock(Map<String, Object> block) {
        var out = new HashMap<String, Object>();
        out.put("type", "image_redacted");
        var source = block.get("source");
        if (source instanceof Map<?, ?> sm) {
            Object mediaType = sm.get("media_type");
            Object data = sm.get("data");
            if (mediaType != null) out.put("media_type", mediaType);
            if (data instanceof String s) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(s);
                    out.put("bytes", decoded.length);
                    out.put("sha256", sha256Hex(decoded));
                } catch (IllegalArgumentException e) {
                    out.put("bytes", s.length());
                    out.put("sha256", null);
                }
            }
        }
        return out;
    }

    private static String sha256Hex(byte[] data) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
