package com.btctech.mailapp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemLabelSettingsRequest {

    @NotEmpty(message = "settings list must not be empty")
    @Valid
    private List<@NotNull(message = "setting item must not be null") SystemLabelSettingItemDto> settings;
}

