package com.salescode.cofman.controller;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {

    @GetMapping("/update")
    public String check(){
        return "ok";
    }
}
