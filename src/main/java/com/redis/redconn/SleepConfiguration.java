package com.redis.redconn;

import lombok.Data;

@Data
public class SleepConfiguration {

	private long get = 100;
	private long reconnect = 100;
}
