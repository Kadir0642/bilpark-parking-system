package com.bilpark.backend.controller;

import com.bilpark.backend.service.StreetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public street list (with zones) used by the field app and the citizen page. */
@RestController
@RequestMapping("/api/streets")
@RequiredArgsConstructor
public class StreetController {

    private final StreetService streetService;

    @GetMapping
    public List<StreetService.StreetDto> list() {
        return streetService.listStreets();
    }
}
