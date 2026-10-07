const { Eureka } = require('eureka-js-client');

const client = new Eureka({
  instance: {
    app: 'MEETING-SERVICE',
    hostName: 'localhost',
    ipAddr: '127.0.0.1',
    port: {
      '$': 3000,
      '@enabled': 'true'
    },
    vipAddress: 'MEETING-SERVICE',
    dataCenterInfo: {
      '@class': 'com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo',
      name: 'MyOwn'
    }
  },
  eureka: {
    host: 'localhost',
    port: 8761,
    servicePath: '/eureka/apps/',
    maxRetries: 10,
    requestRetryDelay: 2000
  }
});

module.exports = client;



