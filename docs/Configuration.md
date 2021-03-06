Configuration
=============

Configuration file is read by UI application during startup - currently it's not possible to change it without restart.
The format of configuration is [Typesafe Config](https://github.com/typesafehub/config).

The configuration files defines
* settings of UI itself
* settings of various services used by model
* means of communicating with Apache Flink cluster

Sample configuration looks like this:
```config
db {
  url: "jdbc:hsqldb:file:db/db;sql.syntax_ora=true"
  driver: "org.hsqldb.jdbc.JDBCDriver"
  user: "SA"
  password: ""
  connectionTimeout: 30000
}

categoriesConfig: {
  "FraudDetection": "streaming",
  "Recommendations": "streaming",
  "Default": "streaming"
}

usersFile: "./conf/users.conf"
environment: "demo"
attachmentsPath: "/tmp/touk/esp-frontend/attachments"
       
processTypes {
  streaming {
    engineConfig {    
      type: "flinkStreaming"
      restUrl: "http://localhost:8081"
      parallelism: 4
      jobManagerTimeout: 1m
    } 
    modelConfig {
      classPath: ["code-assembly.jar"]
      timeout: 10s
      checkpointConfig {
          checkpointInterval: 10m
          minPauseBetweenCheckpoints: 5m
          maxConcurrentCheckpoints: 1
          tolerableCheckpointFailureNumber: 6
      }
      restartInterval: "10s"
      kafka = {
        kafkaAddress = "kafka:9092"
      }
    }
  }
}


metricsSettings {
  url: "http://localhost:3000/dashboard/db/$dashboard?theme=dark&var-processName=$process&var-env=demo"
  defaultDashboard: "flink-esp"
  processingTypeToDashboard: {
    "request-response": "standalone",
    "streaming": "flink-esp"
  }
}

countsSettings {
  influxUrl: "http://localhost:3000/api/datasources/proxy/1/query"
  user: "admin"
  password: "admin"
}

```
In the next sections we'll look at 

##UI configuration

###Database

###Categories
Every process has to belong to a group called category. For example, in one Nussknacker installation you can 
have processes detecting frauds and those implementing marketing campaigns. Category configuration looks like this:
```
categoriesConfig: {
  "marketing": "streaming",
  "fraud": "streaming",
}
```
For each category you have to define its processing type (`streaming` in examples above). You can read about processing
types and their configurations below.

###Monitoring config
```
metricsSettings {
  url: "http://localhost:3000/dashboard/db/$dashboard?theme=dark&var-processName=$process&var-env=demo"
  defaultDashboard: "flink-esp"
  processingTypeToDashboard: {
    "request-response": "standalone",
    "streaming": "flink-esp"
  }
}
```

###Akka configuration

In `akka` section you can configure actor system used by GUI, e.g:
```
akka {
  http {
    server.parsing.max-content-length = 300000000 #300MB
  }
}

```

###Other configurations

* `usersFile` - location of file with user configuration
* `environment` - key of environment (used e.g. for alerts) - e.g. test or production
* `attachmentsPath` - location on disk where attachments will be stored

##Processing types 
One installation of Nussknacker can handle many different processing engines - currently the main supported engine is
Flink in streaming mode. Processing engines are defined in `processTypes` section. You can e.g. have two processing
types pointing to separate Flink clusters. Each processing engine has its name (e.g. `flinkStreaming`). 
Processing type configuration consists of two main parts:
* engine configuration
* model configuration

We describe them below

###Enging configuration
Configuration of communication with processing engine. Below we present Flink engine configuration as an example:

```
engingConfig {     
  type: "flinkStreaming"
  restUrl: "http://localhost:8081"
  parallelism: 4
  jobManagerTimeout: 1m
}
```
In this section you can put all configuration values for Flink client. We are using Flink REST API so the only
required parameter is `restUrl` - which defines location of Flink JobManager
* `jobManagerTimeout` (e.g. 1m) - timeout used in communication with Flink cluster

###Process  {#model}

Configuration of processes has few common keys:
*  `classPath` - list of files/URLs with jars with model for processes
*  `timeout` (e.g. 10s)- for synchronous services
*  `checkpointInterval` - deprecated (use `checkpointConfig.checkpointInterval` instead), e.g. 10m
*  `checkpointConfig` (more about checkpoint configuration you can find in [Flink Documentation](https://ci.apache.org/projects/flink/flink-docs-release-{{book.flinkMajorVersion}}/api/java/org/apache/flink/streaming/api/environment/CheckpointConfig.html) - only some options are available)
    * `checkpointInterval` - e.g. 10m
    * `minPauseBetweenCheckpoints` - optional, default to half of `checkpointInterval`, e.g. 5m
    * `maxConcurrentCheckpoints` - optional, default to 1, e.g. 4
    * `tolerableCheckpointFailureNumber` - optional, default 0, e.g. 6

If configuration does not contain `checkpointConfig`, `checkpointInterval` and process does not contain `checkpointInterval` in its properties then checkpoints are not enabled for process.

The rest of model configuration depends on your needs - all the properties defined here will be passed to ```ProcessConfigCreator``` as explained in [API](API.md) documentation.

###Configuration of services
In model configuration you can also define some attributes of services. These include:
* default values of fields
* icons

```
  nodes {
    containsDefaultValue {
      defaultValues {
        parameterName = "parameterValue"
      }
    },
    serviceWithDocumentation {
      docsUrl: "https://en.wikipedia.org/wiki/Customer_service"
    }
    hasSpecialIcon {
      icon: "icon_file.svg"
    }
  }

```
* `containsDefaultValue`, `hasSpecialIcon`, `serviceWithDocumentation` - nodes names
* `parameterName` - node parameter name you'd like to assign default value
* `parameterValue` - value of default parameter
* `docsUrl` - link to documentation (e.g. confluence page)
* `icon`- path to icon file
