FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /app

# # Mount source code and restore
RUN --mount=type=cache,target=/root/.nuget/packages,id=nuget-cache \
    --mount=type=bind,source=./app.csproj,target=./app.csproj \
    --mount=type=bind,source=./packages.lock.json,target=./packages.lock.json \
    dotnet restore ./app.csproj 

# Publish the app using mounted source
RUN \
    ## mount the nuget cache to speed up builds
    --mount=type=cache,target=/root/.nuget/packages,id=nuget-cache \ 
    --mount=type=bind,source=.,target=./src,rw \
    dotnet publish ./src/app.csproj -c Release -o /app/out

FROM mcr.microsoft.com/dotnet/aspnet:8.0-alpine AS runtime
WORKDIR /app
COPY --from=build /app/out ./

ENTRYPOINT ["dotnet", "app.dll"]