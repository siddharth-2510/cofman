package com.salescode.cofman.controller;

import com.salescode.cofman.services.LOBService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/config")
@CrossOrigin("*")
@Slf4j
public class DeployController {


    @Autowired
    LOBService lobService;

    @PostMapping("deploy/{lob}/{env}")
    public Map<String,Boolean> deploy(@PathVariable String lob, @PathVariable String env){
        return lobService.pushToEnv(env, lob);
    }

    @PostMapping("deploy/{lob}/{env}/{name}/{type}")
    public boolean deployOne(@PathVariable String lob,@PathVariable String env,@PathVariable String name,@PathVariable String type){
        return lobService.pushToEnv(name,type,lob,env);
    }
}
