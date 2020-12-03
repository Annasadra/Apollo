/*
 * Copyright (c) 2020-2021. Apollo Foundation
 */

package com.apollocurrency.aplwallet.api.dto.attachment;

import com.apollocurrency.aplwallet.api.dto.AppendixDTO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MonetarySystemPublishExchangeOfferDTO extends AppendixDTO {
    @JsonProperty("currency")
    public String currencyId;
    public long buyRateATM;
    public long sellRateATM;
    public long totalBuyLimit;
    public long totalSellLimit;
    public long initialBuySupply;
    public long initialSellSupply;
    public int expirationHeight;
}