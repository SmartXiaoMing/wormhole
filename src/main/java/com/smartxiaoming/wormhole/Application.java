package com.smartxiaoming.wormhole;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.smartxiaoming.wormhole.Application.Stream.StateReady;
import static com.smartxiaoming.wormhole.Application.Stream.StateUnset;

@SpringBootApplication
@ComponentScan("com.smartxiaoming")
@RequestMapping("/")
@Controller
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    static final Logger log = LoggerFactory.getLogger(Application.class);
    static final ObjectMapper objectMapper = new ObjectMapper();

    static class Stream {
        static final int StateUnset = 0;
        static final int StateReady = 1;
        int state = StateUnset;
        MultipartFile multipartFile;
    }
    ConcurrentHashMap<String, Stream> codeStreamMap = new ConcurrentHashMap<>();

    @GetMapping("/blackhole")
    public String blackhole() {
        return "/from.html";
    }

    @GetMapping("/blackhole/{code}")
    public String blackholeWithCode(@PathVariable String code) {
        return "/from.html";
    }

    @PostMapping("/blackhole/{code}")
    @ResponseBody
    public String upload(@PathVariable String code, @RequestParam("file") MultipartFile file) {
        log.info("enter uploadFile, code:{}", code);
        Stream stream = null;
        synchronized (this) {
            stream = codeStreamMap.get(code);
            if (stream == null) {
                stream = new Stream();
                codeStreamMap.put(code, stream);
            }
            if (stream.state == StateReady) {
                return makeResponse(-1, "the code is in using, try another");
            }
            stream.multipartFile = file;
            stream.state = StateReady;
        }
        synchronized (stream) {
            stream.notify();
        }
        synchronized (stream) {
            if (stream.multipartFile != null) {
                try {
                    stream.wait();
                    log.info("waiting file transferred");
                } catch (Exception e) {
                    log.info("error to wait", e);
                    return makeResponse(-1, "error to transfer the file");
                }
            }
        }
        return makeResponse(0, "the file has been transferred");
    }

    @GetMapping("/whitehole")
    public String whitehole() {
        return "/whitehole.html";
    }

    @GetMapping("/whitehole/{code}")
    public String download(@PathVariable String code, HttpServletResponse response) {
        log.info("enter downloadFile, key:{}", code);
        try {
            Stream stream = null;
            synchronized (this) {
                stream = codeStreamMap.get(code);
                if (stream == null) {
                    stream = new Stream();
                    stream.state = StateUnset;
                    codeStreamMap.put(code, stream);
                }
            }
            synchronized (stream) {
                try {
                    if (stream.multipartFile == null) {
                        stream.wait(60000);
                    }
                    if (stream.multipartFile != null) {
                        response.setContentType(stream.multipartFile.getContentType());
                        response.setHeader("Content-Disposition", "attachment; filename=" + stream.multipartFile.getOriginalFilename()); // TODO
                        streamCopy(stream.multipartFile.getInputStream(), response.getOutputStream());
                    } else {
                        return "/to.html";
                    }
                } catch (InterruptedException e) {
                    log.error("timeout to download, code:{}", e);
                    return makeResponse(-1, "timout, no file came");
                } catch (Exception e) {
                    log.error("error to download, code:{}", e);
                    return makeResponse(-1, "internal error");
                }
            }
            return "";
        } finally {
            codeStreamMap.remove(code);
        }
    }

    static String generateCode() {
        return String.valueOf(new Random().nextInt(1000000) + 1000000).substring(1, 6);
    }

    static String uuid() {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }

    static long streamCopy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[4096];
        long count;
        int n;
        for(count = 0L; -1 != (n = input.read(buffer)); count += (long)n) {
            output.write(buffer, 0, n);
        }
        return count;
    }

    static void closeQuietly(Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable t) {
        }
    }

    static String makeResponse(int code, String msg) {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("code", code);
            m.put("msg", msg);
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            log.error("error to makeResponse, code:{}, msg:{}", code, msg);
            return "";
        }
    }
}
