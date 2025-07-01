FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /app

# COPY *.csproj ./
# RUN dotnet restore

# Mount source code and restore
RUN --mount=type=bind,source=./app.csproj,target=./app.csproj \
    dotnet restore ./app.csproj

# Publish the app using mounted source
RUN --mount=type=bind,source=.,target=./src,rw \
    dotnet publish ./src/app.csproj -c Release -o /app/out

FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS runtime
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/out ./

# Enable debugging (optional, for VS Code remote container)
ENV DOTNET_USE_POLLING_FILE_WATCHER=true
ENV ASPNETCORE_URLS=http://+:5000
EXPOSE 5000

ENTRYPOINT ["dotnet", "app.dll"]