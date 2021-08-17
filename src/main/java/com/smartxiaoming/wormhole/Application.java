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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@SpringBootApplication
@ComponentScan("com.smartxiaoming")
@RequestMapping("")
@Controller
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    static final Logger log = LoggerFactory.getLogger(Application.class);
    static final ObjectMapper objectMapper = new ObjectMapper();

    static class Stream {
        boolean transferring = false;
        MultipartFile multipartFile;
    }
    ConcurrentHashMap<String, Stream> codeStreamMap = new ConcurrentHashMap<>();

    @GetMapping({"", "/", "/index", "/index.htm"})
    public String index() {
        return "/index.html";
    }

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
        log.info("enter upload, code:{}, filename:{}", code, file.getOriginalFilename());
        try {
            Stream stream = getStream(code);
            synchronized (stream) {
                if (stream.multipartFile != null) {
                    return makeResponse(-1, "the code is in using, try another");
                }
                stream.multipartFile = file;
                stream.notify();
            }
            synchronized (stream) {
                if (stream.multipartFile != null) {
                    try {
                        log.info("waiting file transferred");
                        stream.wait(60000);
                    } catch (Exception e) {
                        log.info("error to wait, code:{}", code, e);
                        return makeResponse(-1, "error to transfer the file");
                    }
                }
            }
            if (stream.transferring) {
                return makeResponse(0, "some one is downloading the file");
            } else if (stream.multipartFile != null) {
                return makeResponse(-1, "timeout, no one download the file");
            } else {
                return makeResponse(0, "the file has been downloaded by some body");
            }
        } finally {
            codeStreamMap.remove(code);
        }
    }

    @GetMapping("/whitehole")
    public String whitehole() {
        return "/to.html";
    }

    @GetMapping("/whitehole/{code}")
    public void download(@PathVariable String code, HttpServletResponse response) throws IOException {
        log.info("enter download, code:{}", code);
        String msg = "";
        try {
            Stream stream = getStream(code);
            synchronized (stream) {
                try {
                    if (stream.multipartFile == null) {
                        stream.wait(60000);
                    }
                    if (stream.multipartFile != null) {
                        response.setContentType(stream.multipartFile.getContentType());
                        String filename = URLEncoder.encode(stream.multipartFile.getOriginalFilename(),"UTF-8");
                        response.setHeader("Content-Disposition", "attachment; filename=" + filename);
                        stream.transferring = true;
                        streamCopy(stream.multipartFile.getInputStream(), response.getOutputStream());
                        stream.multipartFile = null;
                        stream.transferring = false;
                    } else {
                        msg = "timeout-no-file-found";
                    }
                } catch (InterruptedException e) {
                    log.error("timeout to download, code:{}", code, e);
                    msg = "timeout-no-file-found";
                } catch (Exception e) {
                    log.error("error to download, code:{}", code, e);
                    msg = "internal-error-retry";
                }
                stream.notify();
                if (!msg.isEmpty()) {
                    String url = "/to.html?msg=" + msg;
                    log.info("download, code:{}, redirect:{}", code, url);
                    response.sendRedirect(url);
                }
            }
        } finally {
            codeStreamMap.remove(code);
        }
    }

    Stream getStream(String code) {
        synchronized (this) {
            Stream stream = codeStreamMap.get(code);
            if (stream == null) {
                stream = new Stream();
                codeStreamMap.put(code, stream);
            }
            return stream;
        }
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

    static String makeResponse(int code, String msg) {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("code", code);
            m.put("msg", msg);
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            log.error("error to makeResponse, code:{}, msg:{}", code, msg, e);
            return "";
        }
    }
}
