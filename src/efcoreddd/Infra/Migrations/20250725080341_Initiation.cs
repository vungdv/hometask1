using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace efcoreddd.Infra.Migrations
{
    /// <inheritdoc />
    public partial class Initiation : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "Contracts",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ContractNumber = table.Column<string>(type: "text", nullable: false),
                    DateInitiated = table.Column<DateOnly>(type: "date", nullable: false),
                    CurrentVersionId = table.Column<Guid>(type: "uuid", nullable: false),
                    FinalVersionId = table.Column<Guid>(type: "uuid", nullable: false),
                    Completed = table.Column<bool>(type: "boolean", nullable: false),
                    CompletedDate = table.Column<DateOnly>(type: "date", nullable: false),
                    Fullfilled = table.Column<DateOnly>(type: "date", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Contracts", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "ContractVersion",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ContractId = table.Column<Guid>(type: "uuid", nullable: false),
                    WorkingTitle = table.Column<string>(type: "text", nullable: false),
                    DateCreated = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    DateSentToAuthors = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    AcceptanceDeadline = table.Column<DateOnly>(type: "date", nullable: false),
                    ModificationDetails = table.Column<string>(type: "text", nullable: false),
                    ModificationReason = table.Column<int>(type: "integer", nullable: false),
                    Accepted = table.Column<bool>(type: "boolean", nullable: false),
                    ContractAggregateId = table.Column<Guid>(type: "uuid", nullable: true),
                    _hasRevisedSpecSet = table.Column<bool>(type: "boolean", nullable: false),
                    Authors = table.Column<string>(type: "jsonb", nullable: true),
                    Specs = table.Column<string>(type: "jsonb", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ContractVersion", x => x.Id);
                    table.ForeignKey(
                        name: "FK_ContractVersion_Contracts_ContractAggregateId",
                        column: x => x.ContractAggregateId,
                        principalTable: "Contracts",
                        principalColumn: "Id");
                });

            migrationBuilder.CreateIndex(
                name: "IX_ContractVersion_ContractAggregateId",
                table: "ContractVersion",
                column: "ContractAggregateId");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "ContractVersion");

            migrationBuilder.DropTable(
                name: "Contracts");
        }
    }
}
