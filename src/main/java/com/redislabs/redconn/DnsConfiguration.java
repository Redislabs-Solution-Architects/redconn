package com.redislabs.redconn;

import lombok.Data;

@Data
public class DnsConfiguration {
	private String ttl = "0";
	private String negativeTtl = "0";
}
