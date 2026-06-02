docker run --name my-postgres-de `
  -e POSTGRES_PASSWORD=valkeyry `
  -e LANG=de_DE.utf8 `
  -e POSTGRES_INITDB_ARGS="--locale=de_DE.utf8" `
  -v "C:\Valkeyry\Git_Hub\valkeyry\postgres_schema\init.sql:/docker-entrypoint-initdb.d/init.sql" `
  -p 5432:5432 `
  -d postgres:16-alpine


docker rm -f my-postgres-de