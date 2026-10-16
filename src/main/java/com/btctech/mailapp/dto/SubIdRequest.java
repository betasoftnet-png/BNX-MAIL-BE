package com.btctech.mailapp.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SubIdRequest {

    @NotBlank(message = "Prefix is required")
    private String prefix;

    @NotBlank(message = "Password is required")
    private String password;

    private String firstName;
    private String lastName;

    @NotBlank(message = "Account Type is required (PERSONAL or BUSINESS)")
    private String accountType; // "PERSONAL" or "BUSINESS"
    
    @JsonProperty("branchId")
    @JsonAlias({"branch_id", "branchId"})
    private Long branchId;

    @JsonProperty("branchName")
    @JsonAlias({"branch_name", "branchName"})
    private String branchName;

    private java.util.List<Integer> permissions;
}
