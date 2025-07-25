using System;
using Microsoft.EntityFrameworkCore.Migrations;
using Npgsql.EntityFrameworkCore.PostgreSQL.Metadata;

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
                    Specs_AdvanceAmountUSD = table.Column<int>(type: "integer", nullable: false),
                    Specs_AuthorAvailableForPR = table.Column<bool>(type: "boolean", nullable: false),
                    Specs_DigitalRoyaltyPct = table.Column<int>(type: "integer", nullable: false),
                    Specs_HardCoverRoyaltyPct = table.Column<int>(type: "integer", nullable: false),
                    Specs_PriceForAddlAuthorCopiesUSD = table.Column<decimal>(type: "numeric", nullable: false),
                    Specs_PromoCopiesForAuthor = table.Column<int>(type: "integer", nullable: false),
                    Specs_PublicityProvided = table.Column<bool>(type: "boolean", nullable: false),
                    Specs_SoftCoverRoyaltyPct = table.Column<int>(type: "integer", nullable: false),
                    Specs_TranslationRoyaltyUSD = table.Column<int>(type: "integer", nullable: false)
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

            migrationBuilder.CreateTable(
                name: "Author",
                columns: table => new
                {
                    ContractVersionId = table.Column<Guid>(type: "uuid", nullable: false),
                    Id = table.Column<int>(type: "integer", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    Name_FirstName = table.Column<string>(type: "text", nullable: false),
                    Name_LastName = table.Column<string>(type: "text", nullable: false),
                    Email = table.Column<string>(type: "text", nullable: false),
                    Phone = table.Column<string>(type: "text", nullable: false),
                    Signed = table.Column<bool>(type: "boolean", nullable: false),
                    SignedAuthorId = table.Column<Guid>(type: "uuid", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Author", x => new { x.ContractVersionId, x.Id });
                    table.ForeignKey(
                        name: "FK_Author_ContractVersion_ContractVersionId",
                        column: x => x.ContractVersionId,
                        principalTable: "ContractVersion",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
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
                name: "Author");

            migrationBuilder.DropTable(
                name: "ContractVersion");

            migrationBuilder.DropTable(
                name: "Contracts");
        }
    }
}
