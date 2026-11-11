package com.btctech.mailapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemLabelSettingItemDto {

    @NotBlank(message = "labelName is required")
    private String labelName;

    @NotNull(message = "showInLabelList is required")
    private Boolean showInLabelList;
}

