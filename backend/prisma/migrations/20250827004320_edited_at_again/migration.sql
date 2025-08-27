-- AlterTable
ALTER TABLE "public"."Contact" ALTER COLUMN "editedAt" DROP NOT NULL,
ALTER COLUMN "editedAt" DROP DEFAULT;
