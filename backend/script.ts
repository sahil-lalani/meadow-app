import { PrismaClient } from "./generated/prisma";

const prisma = new PrismaClient();

async function main() {
    const contact = await prisma.contact.create({
        data: {
            firstName: "Johns",
            lastName: "Does",
            phoneNumber: "1234567890",
        }
    })
    const contacts = await prisma.contact.findMany();
    console.log(contacts);
}

main()
    .catch(e => {
        console.error(e.message);
    })
    .finally(async () => {
        await prisma.$disconnect();
    });