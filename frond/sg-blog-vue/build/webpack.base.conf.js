'use strict'

const path = require('path')
const webpack = require('webpack')
const HtmlWebpackPlugin = require('html-webpack-plugin')
const CopyWebpackPlugin = require('copy-webpack-plugin')
const config = require('../config')

const root = path.resolve(__dirname, '..')
const cssLoaders = ['vue-style-loader', 'css-loader', 'postcss-loader']

module.exports = function (production) {
  return {
    context: root,
    entry: { app: './src/main.js' },
    output: {
      path: config.build.assetsRoot,
      filename: production ? 'static/js/[name].[chunkhash].js' : 'static/js/[name].js',
      chunkFilename: production ? 'static/js/[id].[chunkhash].js' : 'static/js/[id].js',
      publicPath: production ? config.build.assetsPublicPath : config.dev.assetsPublicPath
    },
    resolve: {
      extensions: ['.js', '.vue', '.json'],
      alias: {
        'vue$': 'vue/dist/vue.esm.js',
        '@': path.join(root, 'src')
      }
    },
    module: {
      rules: [
        {
          test: /\.vue$/,
          loader: 'vue-loader',
          options: {
            loaders: {
              css: cssLoaders,
              less: cssLoaders.concat('less-loader')
            }
          }
        },
        {
          test: /\.js$/,
          loader: 'babel-loader',
          include: [path.join(root, 'src')]
        },
        { test: /\.css$/, use: cssLoaders },
        { test: /\.less$/, use: cssLoaders.concat('less-loader') },
        {
          test: /\.(png|jpe?g|gif|svg)(\?.*)?$/,
          loader: 'url-loader',
          options: { limit: 10000, name: 'static/img/[name].[hash:7].[ext]' }
        },
        {
          test: /\.(woff2?|eot|ttf|otf)(\?.*)?$/,
          loader: 'url-loader',
          options: { limit: 10000, name: 'static/fonts/[name].[hash:7].[ext]' }
        }
      ]
    },
    plugins: [
      new webpack.DefinePlugin({ 'process.env': production ? require('../config/prod.env') : require('../config/dev.env') }),
      new HtmlWebpackPlugin({ filename: 'index.html', template: 'index.html', inject: true }),
      new CopyWebpackPlugin([{ from: path.join(root, 'static'), to: 'static' }])
    ]
  }
}
