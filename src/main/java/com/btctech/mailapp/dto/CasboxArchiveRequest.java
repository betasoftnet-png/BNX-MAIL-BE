package com.btctech.mailapp.dto;

import lombok.Data;
import java.util.List;

@Data
public class CasboxArchiveRequest {
    private List<Long> messageIds;
    private Boolean archived = true;
}
