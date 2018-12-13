package com.redislabs.redconn;

import lombok.Data;

@Data
public class SslConfiguration {

	public enum SslProvider {
		Jdk, OpenSsl
	}

	private SslProvider provider = SslProvider.Jdk;
	private KeystoreConfiguration keystore;
	private TruststoreConfiguration truststore;
}
