package io.hrc.sample.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sample REST controller: POST /tickets/book -> FrameworkGateway -> Saga. */
@RestController
@RequestMapping("/tickets")
public class TicketController {}
