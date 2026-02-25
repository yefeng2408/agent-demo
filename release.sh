#将本地开发环境mvn打包的包名rename为app.jar,然后推送代码至git仓库，云服务器部署就直接执行./deploy/deploy.sh即可
mvn clean package -DskipTests
cp target/*.jar app.jar