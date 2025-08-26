mvn clean
mvn package

if [ $? -eq 0 ]; then
  echo 'Running jar file'

  # run server at port 8080
  java -Xmx256m -jar target/SecretDB-1.0-SNAPSHOT.jar 8080
else
    echo "ERROR: mvn package failed, not launching server"
fi


