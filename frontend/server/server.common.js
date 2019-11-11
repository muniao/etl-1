const logger = require("./logging");
const config = require("./configuration");

function initialize(app) {
  app.use("/api/v1/", require("./routes/api"));
  app.use("/resources/components", require("./routes/components"));
  app.use("/resources/pipelines", require("./routes/pipelines"));
  app.use("/resources/executions", require("./routes/executions"));
}

function start(app) {
  const port = config.port;
  app.listen(port, (error) => {
    if (error) {
      logger.error("Can't start server: ", error);
    }
    logger.info("Listening on port: ", port);
  });
}

module.exports = {
  "initialize": initialize,
  "start": start,
};
