package com.springai.rag;

import org.springframework.boot.SpringApplication;

public class TestSpringAiRagTutorialApplication {

    public static void main(String[] args) {
        SpringApplication.from(SpringAiRagTutorialApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
