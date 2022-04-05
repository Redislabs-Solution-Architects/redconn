package com.redis.redconn;

import lombok.Data;

@Data
public class SleepConfiguration {

	private long get = 1000;
	private long reconnect = 1000;
}
