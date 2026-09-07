package com.example.cs.controller;

import com.example.cs.service.AsrService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin
public class AsrController {

    private final AsrService asrService;
    public AsrController(AsrService asrService) {
        this.asrService = asrService;
    }

    // ★ 语音识别接口（方案B1）：wav 二进制 → 识别文本
    // 失败（key 未配/网络错/余额不足）统一返回 503，前端提示用户
    @PostMapping(value = "/api/asr", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Map<String, String>> asr(@RequestBody byte[] audio) {
        if (audio == null || audio.length == 0) {
            return ResponseEntity.badRequest().build();
        }
        try {
            String text = asrService.transcribe(audio);
            return ResponseEntity.ok(Map.of("text", text));
        } catch (Exception e) {
            return ResponseEntity.status(503).build();
        }
    }
}
