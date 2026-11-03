'use strict'

const webpack = require('webpack')
const config = require('../config')
const createConfig = require('./webpack.base.conf')

const webpackConfig = createConfig(false)
webpackConfig.devtool = config.dev.devtool
webpackConfig.plugins.push(new webpack.HotModuleReplacementPlugin())
webpackConfig.devServer = {
  host: process.env.HOST || config.dev.host,
  port: Number(process.env.PORT || config.dev.port),
  historyApiFallback: true,
  hot: true,
  overlay: config.dev.errorOverlay,
  proxy: config.dev.proxyTable,
  watchOptions: { poll: config.dev.poll }
}

module.exports = webpackConfig
