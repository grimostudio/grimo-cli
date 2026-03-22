package io.github.samzhu.grimo;

import org.springframework.boot.SpringApplication;

public class TestGrimoApplication {

	public static void main(String[] args) {
		SpringApplication.from(GrimoApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
