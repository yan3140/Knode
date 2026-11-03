'use strict'

process.env.NODE_ENV = 'production'

const webpack = require('webpack')
const createConfig = require('./webpack.base.conf')
const config = require('../config')

const webpackConfig = createConfig(true)
webpackConfig.devtool = config.build.productionSourceMap ? config.build.devtool : false
webpackConfig.plugins.push(new webpack.optimize.UglifyJsPlugin({
  compress: { warnings: false },
  sourceMap: config.build.productionSourceMap
}))

webpack(webpackConfig, (error, stats) => {
  if (error) {
    console.error(error)
    process.exitCode = 1
    return
  }
  console.log(stats.toString({ colors: false, chunks: false, modules: false, children: false }))
  if (stats.hasErrors()) process.exitCode = 1
})
