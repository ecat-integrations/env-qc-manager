const path = require("path");
const getCommonWebpackConfig = require("../../../../../env-dev-utils/vue-package/webpack.config.js");
const { VueLoaderPlugin } = require("vue-loader");
// const MiniCssExtractPlugin = require('mini-css-extract-plugin');

// 子模块自定义配置
const customConfig = {
  // 这里可以添加子模块特有的配置，例如额外的插件、规则等
  // 示例：添加一个自定义插件
  // plugins: [new CustomPlugin()]
  plugins: [
    new VueLoaderPlugin(),
    require("unplugin-auto-import/webpack").default({
      // 指定需要自动导入的库
      imports: [
        "vue", // 自动导入 vue 中的 ref, reactive 等 API
        // 可选：如果需要其他库（如 vue-router），可以继续添加
        // 'vue-router'
        // ‘pinia’,
      ],
      dts: false, // 是否生成类型声明文件
    }),
    // new MiniCssExtractPlugin({
    //   filename: '[name].css', // CSS文件名
    // }),
  ],
  module: {
    rules: [
      {
        test: /\.vue$/,
        loader: "vue-loader",
        options: {
          // 确保Vue文件以UTF-8编码处理
          compilerOptions: {
            whitespace: 'preserve',
            // vue-loader 默认把模板 <!-- --> 注释编译成 createCommentVNode：注释出现在根元素前会使组件渲染成
            // Fragment 多根,经宿主 AppMain <transition mode="out-in"> 包裹时间歇挂不上 enter 钩子致 SPA 导航白屏
            // (bug-record-20260807-184548)。comments:false 让注释不进渲染产物,根恒单一元素,从结构上杜绝该白屏复发。
            comments: false
          }
        }
      },
      {
        test: /\.js$/,
        loader: "babel-loader",
        exclude: /node_modules/,
        options: {
          // 确保JavaScript文件以UTF-8编码处理
          presets: [
            ['@babel/preset-env', {
              targets: {
                browsers: ['> 1%', 'last 2 versions']
              }
            }]
          ]
        }
      },
      {
        test: /\.(css|scss)$/,
        use: ["style-loader", "css-loader", "postcss-loader", "sass-loader"],
      },
      {
        test: /\.index\.js$/,
        exclude: /node_modules/,
        use: [
          {
            loader: path.resolve(
              "../../../../../env-dev-utils/vue-package/webpack-loaders/",
              "ecat-tailwind-loader.js"
            ),
            options: {
              cssPath: "assets/styles/tailwind.css", // CSS 路径
              cssImportPath: "@assets/styles/tailwind.css", // 引入 CSS 路径
              testPatterns: [
                /\.index\.js$/, // 匹配 ta 模块的 index.js（正则）
                // 'tb/main.js',        // 匹配 tb 模块的 main.js（字符串，自动转换为 /main.js$/）
                // 'modules/app.js'     // 匹配 modules 目录下的 app.js（字符串）
              ],
            },
          },
        ],
      },
    ],
  },
};

module.exports = () => getCommonWebpackConfig(path.resolve(__dirname), customConfig);
