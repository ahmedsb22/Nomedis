const { Eureka } = require('eureka-js-client');

// Valeurs par defaut = poste de developpement ; surchargeables en conteneur.
//
// ATTENTION : deux adresses distinctes sont publiees dans Eureka.
//
//   * hostName + port  : utilises par la Gateway (lb://CONFERENCE-SERVICE) et par
//     les clients qui appellent ce service DEPUIS le reseau Docker. Il faut donc
//     le nom du service Compose (conference-service) et le port interne (3000) :
//     un conteneur ne peut joindre ni 'localhost' ni le port publie sur l'hote.
//
//   * homePageUrl / statusPageUrl / healthCheckUrl : affichees dans le dashboard
//     Eureka (http://localhost:9761), ouvert depuis le navigateur du poste hote.
//     Elles pointent donc vers le port PUBLIE (3001).
const os = require('os');

const APP_NAME = 'CONFERENCE-SERVICE';
const EUREKA_HOST = process.env.EUREKA_HOST || 'localhost';
const EUREKA_PORT = Number(process.env.EUREKA_PORT) || 8761;
/** Nom publie dans Eureka : resolvable par les autres conteneurs. */
const INSTANCE_HOSTNAME = process.env.INSTANCE_HOSTNAME || 'localhost';
/** Port d'ecoute interne (routage). */
const INSTANCE_PORT = Number(process.env.INSTANCE_PORT) || Number(process.env.PORT) || 3000;
/** Port publie sur l'hote (liens du dashboard). */
const PUBLIC_HOST = process.env.PUBLIC_HOST || 'localhost';
const PUBLIC_PORT = Number(process.env.PUBLIC_PORT) || INSTANCE_PORT;

/** IP joignable par les autres conteneurs (reseau Docker). */
function detectIp() {
  if (process.env.INSTANCE_IP) {
    return process.env.INSTANCE_IP;
  }
  if (EUREKA_HOST === 'localhost') {
    return '127.0.0.1';
  }
  const interfaces = os.networkInterfaces();
  for (const addresses of Object.values(interfaces)) {
    for (const address of addresses || []) {
      if (address.family === 'IPv4' && !address.internal) {
        return address.address;
      }
    }
  }
  return '127.0.0.1';
}

const INSTANCE_IP = detectIp();
const PUBLIC_URL = `http://${PUBLIC_HOST}:${PUBLIC_PORT}`;

const client = new Eureka({
  instance: {
    app: APP_NAME,
    // Mettre l'identifiant en forme cote client : eureka-js-client fabrique par
    // defaut "<host>:<APP>:<port>", ce qui affichait
    // "conference-service:CONFERENCE-SERVICE:3000" dans le dashboard.
    // On garde le meme format que les services Java : "<service>:<port>".
    instanceId: `${INSTANCE_HOSTNAME}:${INSTANCE_PORT}`,
    hostName: INSTANCE_HOSTNAME,
    ipAddr: INSTANCE_IP,
    port: {
      $: INSTANCE_PORT,
      '@enabled': 'true',
    },
    homePageUrl: `${PUBLIC_URL}/`,
    statusPageUrl: `${PUBLIC_URL}/swagger-ui`,
    healthCheckUrl: `${PUBLIC_URL}/`,
    vipAddress: APP_NAME,
    dataCenterInfo: {
      '@class': 'com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo',
      name: 'MyOwn',
    },
  },
  eureka: {
    host: EUREKA_HOST,
    port: EUREKA_PORT,
    servicePath: '/eureka/apps/',
    maxRetries: 10,
    requestRetryDelay: 2000,
  },
});

module.exports = client;
